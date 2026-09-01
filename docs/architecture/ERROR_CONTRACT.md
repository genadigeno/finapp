# Error Contract

Every API failure is returned as an RFC 9457 problem detail with a stable, machine-readable code.
One shape, on every path, including the ones the framework raises before our code runs.

**This document and the code are one definition.**
[`ErrorCodeRegistryTest`](../../app/src/test/java/com/finapp/app/api/ErrorCodeRegistryTest.java)
fails the build when they disagree in *either* direction.

---

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
| `api.PayloadTooLarge` | 413 | The request body exceeded the accepted size. |
| `api.UnsupportedMediaType` | 415 | The body's media type is not read here. |
| `api.ValidationFailed` | 422 | Well-formed, and not valid. |
| `api.InternalError` | 500 | Something failed that the client cannot act on. |

**400 versus 422** is the distinction between "your serialiser is wrong" and "your data is
wrong", and it is worth keeping: a client can act on the second and only a developer can act on
the first.

**A client's mistake is never reported as ours.** Spring's web exceptions carry the status they
mean, and the renderer maps that status to a code rather than letting the catch-all turn it into
a 500. This is not a cosmetic concern: a client may retry a 500 forever on a request that can
never succeed, and a spike of malformed requests would otherwise be indistinguishable from an
outage. An unmapped 4xx becomes `api.MalformedRequest` and is logged as a warning, so the gap is
visible rather than quietly approximated.

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

## 7. Adding a code

1. Add the constant to the owning module's `ErrorCode` enum, with a namespaced code, a status
   that reflects what the caller should do, and a title safe to show anyone.
2. Add the row to §3.
3. Raise it with `ApiException`, putting diagnostics in the log message and only
   stranger-safe text in the client detail.

Steps 1 and 2 are checked against each other by the build.
