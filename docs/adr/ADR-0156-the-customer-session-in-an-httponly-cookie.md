# ADR-0156 — The customer session in an HttpOnly cookie on the public surface, with a CSRF defence

Status: Accepted
Date: 2026-11-20
Phase: 17 (pulled forward from Phase 18 by the owner, 2026-11-20)
Context: Identity (sessions) · the API's session carrier · the Customer Application
Rests on: ADR-0030 (opaque sessions judged in PostgreSQL), ADR-0032 (no JWT, no external IdP), finapp-admin-panel ADR-C131
(the console's in-memory token), finapp-front-end **ADR-F02** (this decision's source, the owner's D-D) and its
`FINAPP_BACKEND_WORK.md` B0.2 and B1.1 (gap G-04); `BACKLOG.md` `X-TSK-029`.

## Context

ADR-0030's session is an opaque token judged in PostgreSQL on every request (idle 30 minutes, absolute 12 hours, rotated on
sign-in, step-up and password change, revocable at once). Until now it travelled only as `Authorization: Bearer`, issued in the
JSON body of `POST /v1/authentications`, `POST /v1/authentications/mfa` and `POST /v1/me/credential`.

The customer application held that token in JavaScript memory (ADR-C131's carrier), so a reload, a new tab or a link opened
from an e-mail signed the customer out. A token readable by JavaScript can also be read by injected script and replayed from
anywhere until it expires. ADR-F02 asked `finapp` to carry the customer's session in an HttpOnly cookie instead; Phase 17's
backlog listed cookie sessions among what it must not implement (the console's D-4). On 2026-11-20 the owner asked for the
most secure way to keep a customer signed in across a reload and, told it was Phase 18's, said "do it": this ADR adopts
ADR-F02 in this register and pulls `B0.2` and `B1.1` forward as `X-TSK-029`.

## Decision

1. **The cookie.** `__Host-fa_session=<token>; Path=/; Secure; HttpOnly; SameSite=Strict`. No `Domain`, no `Max-Age` or
   `Expires`: the server's idle and absolute bounds stay the only expiry, and the browser drops it when it closes.
   *Deviation from ADR-F02's proposal (`fa_session; Path=/v1`), whose name was "final at implementation"*: the `__Host-`
   prefix makes the browser refuse the cookie unless it is `Secure`, host-only and `Path=/`, so a sibling sub-domain or a
   plain-HTTP response cannot plant or overwrite it (cookie tossing, session fixation). A path is not a security boundary
   within one origin; the prefix is. Browsers treat `http://localhost` as secure, so the local stack needs no TLS.
2. **The public surface only.** The cookie is read only when the request's surface is `PUBLIC` (`X-Finapp-Surface: public`,
   set by the public edge, and locally by the customer application's dev proxy). On the operator surface, an unmarked request
   and the single local port it is **ignored**, as if absent - so a console request is authenticated by its bearer token alone
   and staff never authenticate by cookie (`THREAT_MODEL.md` T1, T3).
3. **Issued on opt-in.** A request on the public surface carrying `X-Finapp-Session-Carrier: cookie` opts in: the three doors
   that issue a token today answer `Set-Cookie` with it and **omit `sessionToken` from the body**. Without the header the
   answer is byte-for-byte today's, so the console and any native client are unchanged. One response advice
   (`SessionCookieAdvice`) does this for every response type that issues a token (`IssuesSessionToken`), so a future issuing
   door cannot forget it.
4. **Rotation replaces it.** Every rotation (sign-in, step-up, password change) issues a new cookie over the old. Signing out
   (`DELETE /v1/sessions/current`) and revoking the current session by id answer `Max-Age=0` on the public surface.
5. **CSRF defence, before any handler** (`SessionCookieGuard`, ahead of the session interceptor). On the public surface, every
   unsafe method (`POST`, `PUT`, `PATCH`, `DELETE`) that **presents the cookie or opts in** must carry
   `X-Finapp-Request: 1` **and** an `Origin` in the allow-list; otherwise `403 identity.CsrfRejected`, before the handler, the
   session lookup or any count. Three layers: `SameSite=Strict` (the browser does not attach the cookie to a cross-site
   request); a custom header, which a cross-site form cannot send and a cross-origin script cannot send without a CORS
   preflight that `finapp` never grants; and the `Origin` check, for a browser that honours neither. The opt-in is guarded
   too, so a sign-in cannot be forced into a victim's browser (login CSRF).
6. **The allow-list.** `finapp.session.cookie.allowed-origins` (`FINAPP_SESSION_COOKIE_ALLOWED_ORIGINS`), exact origins.
   **Unset on a deployed instance, it is empty and every cookie request is refused** (fail closed). Unset where nothing is
   deployed (`RequestSurfaces.nothingDeployed()`: no edge network and the database on loopback - the switch of every marked
   local default, ADR-0019/0020), it defaults to the customer application's local origins `http://localhost:5180` and
   `http://localhost:4180`.
7. **One carrier per request.** On the public surface, a request presenting both a bearer header and the cookie is refused
   `401 api.Unauthenticated` - never a guess at which session acts.
8. **Nothing else changes.** Sessions stay in PostgreSQL, judged per request on every replica; assurance, step-up by rotation,
   lockout, per-source limits, revocation and their audit records are untouched. The token never enters a URL, a log, a trace,
   a metric or a response body when the cookie carries it.

## Alternatives Considered

### Option A — keep the token in JavaScript memory (ADR-C131's carrier)
Pros: no backend change, no CSRF surface. Cons: a reload or new tab signs the customer out; injected script can read the
token and replay it from elsewhere. Kept for the console, where staff signing in again is the intended cost.

### Option B — a backend-for-frontend holding the session
Rejected in finapp-front-end ADR-F01: a second deployable and a second session store for what one cookie does.

### Option C — the token in `localStorage` / `sessionStorage`
Rejected outright: persistent and readable by any injected script - the worst of both.

### Option D — ADR-F02's `fa_session; Path=/v1` exactly
Pros: the cookie is not sent with the SPA's static assets. Cons: without `__Host-` a sibling host or a plain-HTTP response can
plant it. Rejected for decision 1's reason; the static assets are served by the same edge, which logs no cookie.

### Option E — Spring Security's CSRF token
Rejected: this codebase has no Spring Security (ADR-0031's interceptor), and a synchroniser token adds state for what
`SameSite=Strict`, a custom header and an `Origin` check already give a same-origin JSON API.

## Consequences

Positive: the customer's token never reaches JavaScript; a reload, a new tab or an e-mailed link keeps the session; revocation
is unchanged and immediate.
Negative: a CSRF surface exists and is defended by decision 5; injected script can still ride the session while the page is
open (the customer application's CSP, ADR-F07, is that control).
Operational impact: a deployed public edge must set `FINAPP_SESSION_COOKIE_ALLOWED_ORIGINS` to the public origin, or the
customer application cannot sign anyone in (fail closed, `403 identity.CsrfRejected`).
Security impact: removes token exfiltration by script on the public surface; the semgrep rule `finapp-spring-csrf-disabled`
needs no baseline entry, because nothing disables a CSRF filter - there is none, and decision 5 is the defence.
Financial impact: none.

## Invariants / Constraints

- `INV-IDN-03`: a revoked session is refused at its next request on every replica - unchanged, the cookie is a carrier only.
- The cookie is never read on the operator surface, an unmarked request or the single port.
- No unsafe request authenticated by the cookie reaches a handler without `X-Finapp-Request: 1` and an allowed `Origin`.

## Follow-up

- A deployed public origin sets `FINAPP_SESSION_COOKIE_ALLOWED_ORIGINS` when the customer application is hosted (`B1.2`, G-15).
- The CSRF refusals are unaudited: they reach no session, so they have no actor (as `api.Unauthenticated`); a meter for them
  is owed if they are ever seen in volume.
