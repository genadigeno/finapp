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

A client **may** supply the header to join its own logs to ours. That value is untrusted:

- allowed characters are `A-Z a-z 0-9 . _ : @ / + = -`
- maximum length 128
- a value failing either rule is **replaced, not sanitised**, and the request still succeeds

Replacement rather than sanitisation is deliberate: a silently rewritten identifier breaks the
client's own correlation without telling anyone — they log one value, we log another, and the two
can never be joined. And a malformed diagnostic hint is never a reason to decline a payment.

*Proven by `RequestValidationTest`; charset and bound live in `CorrelationId`.*

## 5. Request limits and validation — **Implemented**

| Rule | Behaviour |
|---|---|
| Body size | Default **1048576** bytes, configurable by `finapp.api.max-request-bytes` |
| Over the limit | `api.PayloadTooLarge` (413) |
| Body fails declared constraints | `api.ValidationFailed` (422) |
| Body will not parse | `api.MalformedRequest` (400) |

**400 versus 422** is the distinction between "your serialiser is wrong" and "your data is
wrong": a client can act on the second, and only a developer can act on the first.

The limit closes both routes — a declared `Content-Length` over the limit is refused without
reading a byte, and a chunked body, which declares no length at all, is bounded by a counting
stream. A limit that only reads the header is one a caller opts out of by sending chunked.

**Rejected values are never echoed.** A validation detail names the field and the constraint, both
of which are ours; the value is the caller's, and reflecting untrusted bytes into a response is how
an error message becomes a vector (`INV-AUD-02`).

*Proven by `RequestValidationTest`, which counts handler entries rather than reading the response —
a 422 returned after the handler ran and did half the work looks identical from outside.*

## 6. Idempotency — **Decided, not yet implemented** (`P0-TSK-017`, first used in Phase 4)

Nothing reads this header today. There is no money-moving endpoint, and `P0-TSK-017` owns the
implementation.

**Every money-moving command requires `Idempotency-Key`.** Safe methods (`GET`, `HEAD`) ignore it;
a command that can move money and does not require it is a defect, not a relaxation.

| Rule | Behaviour |
|---|---|
| Missing on an endpoint that requires it | Rejected — the request is not attempted |
| Same key, same request | The **original** outcome is returned. Exactly one financial effect (`INV-IDEM-01`) |
| Same key, materially different request | `api.Conflict` (409). Never a silent second effect, never the first response (`INV-IDEM-03`) |
| Key still in progress | Reported as in progress; the caller retries. Never assumed failed |
| Key format | Client-generated, bounded, and validated at the boundary. A UUID is the expected shape |

**The key is scoped, not global.** Two callers must not collide by both choosing `1`, so the
uniqueness constraint covers a scope (the operation, and the authenticated principal once Phase 1
provides one) alongside the key. See ADR-0004.

**It is enforced by a database constraint**, never by a cache or an HTTP filter — a filter
deduplicates requests, and what must be deduplicated is *financial effects*.

**Keys expire.** Retention is a correctness bound rather than housekeeping: too short and a client
retrying after an outage gets a second effect; too long and the table grows without limit
(`DATA_MIGRATIONS.md` §8). A retry after expiry is a new request.

**Logging:** the key is not sensitive and is not redacted, but it is recorded on the audit record
for the action, so a disputed operation can be traced to the request that caused it.

## 7. Pagination — **Decided, not yet implemented** (first collection endpoint, Phase 3+)

No endpoint returns a collection today.

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

## 8. Deprecation — **Decided, not yet implemented** (first deprecation)

Within a version, evolution is additive. Nothing is deprecated today, so no mechanism is built;
building one now would be untested machinery.

| Step | What happens |
|---|---|
| Mark | `deprecated: true` in the OpenAPI document, so the deprecation is part of the published contract rather than an announcement someone may miss |
| Announce | `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) response headers, plus `Link rel="deprecation"` to the replacement |
| Wait | At least **6 months** from the first `Deprecation` header to removal. A superseded **version** is served at least **12 months** after its successor is generally available |
| Remove | Only in the next version, never inside the current one |

**A money-moving endpoint carries one further obligation:** before removal, the reconciliation and
audit consequences are considered explicitly. Records referencing it remain, and operations that
must still be explainable outlive the endpoint that created them.

## 9. What is deliberately not decided here

Recorded so their absence is not mistaken for an oversight.

| Not decided | Owning phase |
|---|---|
| Authentication and authorization at the boundary — schemes, token shape, `401` versus `403` in practice | Phase 1 (`P0-EPIC-10` provides the Phase 0 baseline) |
| Rate limiting and the response that signals it | Phase 13 (limits and velocity) |
| Filtering and sorting parameter grammar | The first endpoint that needs one |
| Bulk and batch request shapes | The first capability that needs one |
| Asynchronous request/response (`202` and its polling contract) | Phase 5, with payment lifecycles |
| Webhook delivery, signing and retry semantics | Phase 5 |
