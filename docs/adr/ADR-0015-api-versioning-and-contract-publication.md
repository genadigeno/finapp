# ADR-0015 — API versioning in the path, with the contract generated and compared on every build

Status: Proposed

Date: 2026-09-02

## Context

`P0-TSK-024` and `P0-TSK-025` gave the platform its first outward-facing surface: an RFC 9457
error contract with eleven codes, a problem-detail shape, and a correlation identifier echoed on
every response. That is already a contract, and clients written against it can already be broken
by us.

`.claude/rules/api-design.md` requires backwards-compatible evolution. Two questions have to be
answered before the first business endpoint exists, because both become expensive afterwards:

**1. Where does the version live?** An API that has ever been called cannot be changed
incompatibly without a second version to move clients to. Deciding the mechanism after endpoints
exist means either retrofitting every route or living with an unversioned surface for ever.

**2. What stops an incompatible change reaching a client?** `ERROR_CONTRACT.md` §4 already
describes the failure precisely for error codes: renaming one is *"a breaking change wearing a
refactor's clothes, and unlike a broken build it fails at the customer's end"*. The same is true
of removing a field, tightening a constraint or changing a status. Review does not catch these
reliably; a reviewer sees a diff of code, not a diff of the contract.

This platform has a further reason to care that a generic web service does not. `CLAUDE.md`
§Security and Audit requires a consequential action to be reconstructable — actor, time,
operation, target, correlation, outcome. Which version of the contract a call was made against is
part of what happened.

## Decision

**1. The version is a path prefix, `/v1`, applied centrally.**

Every route this platform publishes is served under `/v{n}`. Controllers do not declare it:
`ApiVersionConfiguration` applies it once, to every handler in the `com.finapp` package tree, via
Spring's `PathMatchConfigurer`. A controller therefore cannot express an opinion about versioning
and cannot forget to.

`ApiVersion` in `platform` holds the number; the segment and the prefix are derived from it, so a
version bump is one edit rather than one edit and two things to remember. It sits beside
`ErrorCode` and `ProblemDetail` because a version is contract vocabulary, not a routing detail
(`ERROR_CONTRACT.md` §6 makes the same argument).

**2. The number increments only for a breaking change.**

There is no minor component. A minor version that never breaks anything is a number clients would
have to send and could never act on. Everything backwards compatible — a new endpoint, a new
optional field, a new error code — happens inside the current version.

**3. Operational endpoints are not versioned.**

Health, readiness and metrics are consumed by orchestrators and scrapers whose configuration is
deployment-scoped, not by clients holding a contract. Moving them on a version bump would break a
liveness probe for no benefit. This falls out of the mechanism rather than needing an exception:
actuator endpoints are served by their own handler mapping, which `PathMatchConfigurer` does not
touch.

**4. The OpenAPI document is generated from the running application on every build, and compared
byte for byte against a committed copy.**

`docs/api/openapi.json` is the published contract. `OpenApiContractTest` boots the application,
generates the document from the live request mappings, and **fails the build on any difference**.
`OpenApiChange` then labels each difference `BREAKING` or `COMPATIBLE` so the failure message says
which decision is being asked for.

The gate and the classifier are deliberately separate. A classifier that *gated* would have to be
right about every possible OpenAPI edit, and its one dangerous mistake — calling a breaking change
compatible — fails at the customer's end, months later, in someone else's code. An exact
comparison has no such failure mode. Advice that is occasionally wrong costs a misleading label on
a failure someone is already reading.

**5. Nothing about OpenAPI ships.**

springdoc is a test-scope dependency. It *reads* the request mappings and never changes them, so a
document generated with it on the test classpath describes exactly the application that is
deployed — while the deployed application carries no documentation library and no
`/v3/api-docs` endpoint for anyone to find. The contract is a reviewed artefact in git, not a live
endpoint that changes with a deployment.

Nothing is *authored* in the generator either. The error responses come from every declared
`ErrorCode`, the problem-detail members from `ProblemDetailBody` (where the wire format is
decided), and which of them are always present from `ProblemDetail` (where optionality is already
spelled `Optional`). Adding an error code changes the document with nobody editing it.

**6. Deprecation policy.**

Within a version, evolution is additive. When something must be retired:

| Step | What happens |
|---|---|
| Mark | The operation or member is `deprecated: true` in the OpenAPI document, which makes the deprecation part of the published contract rather than a mailing-list announcement |
| Announce | Responses carry `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) headers, plus `Link rel="deprecation"` to the replacement |
| Wait | At least **6 months** between the `Deprecation` header first appearing and removal. A superseded *version* is served for at least **12 months** after its successor is generally available |
| Remove | Only in the next version, never within the current one |

Two versions are served concurrently during an overlap by adding a second configurer that prefixes
a `v2` handler package. No existing controller is edited.

**A money-moving endpoint carries one extra obligation**: before it is removed, the reconciliation
and audit consequences are considered explicitly. Records referencing it remain, and the operations
that must still be explainable outlive the endpoint that created them.

The mechanism for the `Deprecation` and `Sunset` headers is **not built**, because nothing is
deprecated and speculative machinery is machinery nobody tests. It is owed by the first
deprecation.

## Alternatives Considered

### A. Version in the path (chosen)
Pros: Visible in an access log, an audit record, a proxy cache key, a firewall rule, and a `curl`
pasted into a support ticket. Trivially routable. No client-side negotiation to get wrong. Testable
with a browser.
Cons: A URI is supposed to identify a resource, and this gives the same resource two names. Real,
and a purity argument; it loses to operability here.

### B. Version in a custom header, or in the `Accept` media type
Pros: URIs stay stable and REST-pure. Fine-grained per-resource versioning is possible.
Cons: Invisible everywhere it matters. Reconstructing which contract a call used would need a
packet capture, which defeats §Security and Audit. Caching requires `Vary` discipline that
intermediaries get wrong. Every client and every debugging session must remember to set it, and the
default when it is absent is a decision with no good answer — serve the oldest and you never
retire it, serve the newest and a silent client breaks on your release day.

### C. Version in a query parameter
Pros: Visible in logs; easy to set.
Cons: Trivially omitted, so an implicit default is unavoidable, with the same bad choice as B. Mixes
contract selection with resource selection in one namespace.

### D. Spring Framework 7's built-in API versioning (`@RequestMapping(version = ...)`)
Pros: Idiomatic on this stack; supports several strategies including a path segment; gives
per-handler version ranges.
Cons: It is a *negotiation* mechanism, and negotiation is not the problem being solved. It puts the
version back on each handler, which is the property option A deliberately removes, and it commits
the platform to a framework feature for something a five-line configurer does. Revisit if
per-endpoint version ranges are ever genuinely needed.

### E. No versioning until an endpoint exists
Pros: Nothing speculative.
Cons: The first endpoint would arrive unversioned, and the second one would have to retrofit every
route. The cost of the seam now is one configuration class; the cost later is a migration.

### F. Contract-first: hand-write the OpenAPI document and verify code against it
Pros: The contract is designed, not emitted.
Cons: Verifying an implementation against a hand-written document is a much larger mechanism than
comparing two generated documents, and a hand-written document can be wrong about the
implementation in ways nothing detects. Generating from the application means the document cannot
lie about what is served; committing it means it cannot change without review. That combination
gets most of contract-first's benefit at a fraction of the machinery.

### G. Classify breaking changes with a full OpenAPI diff library
Pros: Far more rules than the ~4 implemented here; understands request versus response position.
Cons: A third-party beta artefact on the build's critical path, pulling a second Jackson generation
in. And it would not change the gate, which is exact equality — only the labels on a failure a
human is already reading. Revisit if the label quality becomes a real cost, which it will when the
document has many endpoints.

## Consequences

Positive:
- A contract change cannot reach a client without a human seeing a build failure that names it.
- The version is present in every artefact an investigation reads.
- Compatible evolution is cheap: regenerate, read the labels, copy the file.
- No documentation library, Swagger model, or unauthenticated documentation endpoint is deployed.
- The status, code and problem type of every error are published as **data**, so a client can learn
  them without parsing prose.

Negative:
- Every legitimate contract change costs a second commit step — accepting the regenerated baseline.
  That is the intended friction, but friction that is never justified becomes a rubber stamp, which
  is why the failure message classifies rather than merely reporting a mismatch.
- The classifier reasons about JSON-pointer shape, not about OpenAPI semantics. It cannot tell a
  request schema from a response schema, so an added `enum` value is called breaking in both
  directions when strictly it is breaking in only one. It errs towards breaking.
- URIs are versioned, which is a genuine REST compromise.

Operational impact:
- `docs/api/openapi.json` is the artefact to hand to a client or a partner.
- Serving two versions concurrently is a routing change, not a rewrite.

Security impact:
- Removing springdoc from the runtime removes an unauthenticated endpoint that describes the whole
  API surface.
- springdoc synthesises a `servers` entry from the request it is answering. That is stripped: left
  in, a deployed document would publish an internal address to every client, and the committed
  document would differ on every test run.

Financial impact:
- None directly. Indirectly: a breaking change to a money-moving endpoint that reaches an integrated
  partner is an outage in someone else's payment flow, and this is the mechanism that prevents one
  shipping unnoticed.

## Invariants / Constraints

- `INV-AUD-02` — the document must not publish an internal address, a hostname or anything else
  about our deployment. The `servers` block is removed for exactly this reason.
- `ERROR_CONTRACT.md` §4 — codes are permanent. The comparison is what enforces it now.
- `INV-HIST-04` — versioned artefacts used in a decision are pinned and recorded. The API version is
  such an artefact once an endpoint moves money; the path prefix is what makes it recorded.
- `ADR-0007` — this is a seam, not future-phase functionality. It publishes only the contract that
  already exists.

## Follow-up

- **`Deprecation` and `Sunset` headers** are policy, not mechanism. Owed by the first deprecation.
- **`P0-DOC-003`** (API conventions) restates the client-facing half of this decision alongside
  pagination and idempotency conventions.
- **`P0-TSK-017`** declares `Idempotency-Key` on money-moving commands; that header becomes part of
  this document when an endpoint exists to carry it.
- **Publishing the document** to a developer portal, and whether it should be an attached build
  artefact, belongs with Phase 15's supply-chain and provenance work.
- **Revisit option G** when the document has enough endpoints that pointer-shaped labels stop being
  informative.
