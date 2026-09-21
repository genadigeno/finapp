# ADR-0052 — Merchant API identity: scoped API keys under the credential disciplines, tenancy in the statement

Status: Proposed
Date: 2026-09-21
Phase: 6
Context: Merchant · Identity
Supersedes: nothing.

## Context

The delivery plan requires "merchant API authentication distinct from customer
authentication" and "strict tenant isolation so no merchant can read another's data".
Phase 6 is the first phase whose API callers are **machines operated by counterparties the
platform does not own** — not customers in a session, not operators with permissions, not
a provider signing a webhook. Two decisions are needed before the first merchant endpoint
exists: what a merchant credential *is*, and where tenancy is enforced.

The platform already has three authentication vocabularies: customer sessions
(Phase 1), operator permissions (`@RequiresPermission`), and signed callbacks
(`SIGNED_CALLBACK`, Phase 2/5). None fits: a merchant integration is a server, not a
browser session; it is not a platform operator; and it initiates requests rather than
answering ours.

## Decision

1. **A merchant authenticates with an API key** — the industry-standard shape for
   server-to-server merchant integrations, and the one that reuses the platform's existing
   credential disciplines wholesale:
   - never recoverable (`INV-IDN-01`): stored as a hash under the recorded derivation
     parameters (`INV-IDN-02`), shown once at creation, rotation creates a new key and
     revokes the old after an overlap window;
   - a public **key id prefix** travels with the secret so lookup never scans hashes;
   - transported only as a header over TLS; never in a URL (`INV-AUD-02`);
   - revocation is immediate (`INV-IDN-03`'s discipline applied to keys).
2. **The authenticated subject is the merchant, a new actor type.** `ActorType.MERCHANT`
   joins the audit vocabulary; every merchant-API audit record names the merchant and the
   key id that acted. A merchant API key carries **no operator permission and no customer
   session capability** — the three populations stay disjoint by type, not by convention.
3. **Tenancy is enforced in the statement, not after it** — the ADR-0031 discipline
   (`party_id = ?` in the `UPDATE`) promoted to the tenant boundary: every merchant-scoped
   read and write carries `merchant_id = ?` derived from the authenticated key, so a
   cross-tenant row is **unreadable and unwritable at the SQL level**, and absence and
   another-tenant's-data are one indistinguishable refusal (the one-404 oracle discipline,
   `INV-MER-01`).
4. **Merchant onboarding and key issuance are operator acts** in Phase 6 — privileged,
   audited, behind the existing operator authentication. A merchant self-service dashboard
   (merchant *users*, roles within a merchant) is a later product surface, deliberately out
   of scope; nothing in this decision precludes it.
5. **Checkout sessions are presented to customers with single-purpose unguessable session
   tokens**, not merchant keys: the merchant's server creates the session with its API key;
   the customer completes it with the session's own token, which grants access to exactly
   that session and nothing else.

## Consequences

- No new secret-handling machinery: hashing, derivation-parameter records, externalised
  pepper/keys and the audit ceremony all exist (Phases 1–5); the key joins them as one more
  credential kind.
- The negative tests the phase gate demands ("cross-merchant data access is impossible,
  negative tests per endpoint") have a uniform mechanical shape: authenticate as merchant
  A, address merchant B's resource, assert the one refusal and zero rows touched.
- Webhooks **to** merchants (the platform signing outbound event notifications) reuse the
  `SIGNED_CALLBACK` vocabulary in reverse and are a backlog concern, not a new decision:
  the platform signs with a per-merchant secret exactly as it verifies provider signatures.
- Rate limiting per key remains Phase 15 with the standing per-source limitation row.
