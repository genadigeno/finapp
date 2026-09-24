# ADR-0052 — Merchant API identity: scoped API keys under the credential disciplines, tenancy in the statement

Status: Accepted (2026-09-24, `P6-DOC-001` — read against the implementation at the phase review; four passages corrected to it, and two defects in the code fixed, first)
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
     parameters (`INV-IDN-02`), shown once at creation. Rotation is two audited operator acts,
     issuing the new key and then revoking the old once the merchant has switched: the overlap
     is the interval between them, and no automatic overlap window is built *(the phase review,
     `P6-DOC-001`, found this line promising one)*;
   - a public **key id prefix** travels with the secret so lookup never scans hashes;
   - transported only as a header - over TLS, which is the deployment's posture (ADR-0023) -
     and never in a URL (`INV-AUD-02`);
   - revocation is immediate (`INV-IDN-03`'s discipline applied to keys).
2. **The authenticated subject is the merchant, a new actor type.** `ActorType.MERCHANT`
   joins the audit vocabulary; every audit record a merchant-API command writes names the
   merchant and the key id that acted. *(The checkout session's two records did not until the
   phase review, `P6-DOC-001`, which made them.)* A record another module writes inside the
   same transaction - the ledger's `HoldPlaced` under a merchant's payout - names the merchant
   as its actor and carries the command's correlation id, through which the key is one join
   away: the ledger has no key vocabulary, and learning one is not its job. A merchant API key carries **no operator permission and no customer
   session capability** — the three populations stay disjoint by type, not by convention.
3. **Tenancy is enforced in the statement, not after it** — the ADR-0031 discipline
   (`party_id = ?` in the `UPDATE`) promoted to the tenant boundary: every merchant-scoped
   read and write carries `merchant_id = ?` derived from the authenticated key - the
   idempotency claim of a merchant command included, whose scope names the merchant
   (ADR-0004; the session's claim shared one namespace across merchants until `P6-DOC-001`) -
   so a cross-tenant row is **unreadable and unwritable at the SQL level**, and absence and
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

- No new secret-handling machinery: hashing, derivation-parameter records and the audit
  ceremony all exist (Phases 1–5); the key joins them as one more credential kind. **Its hash
  takes no pepper**, deliberately: a pepper protects a secret a person chose, which can be
  guessed, and a 32-byte random secret leaves nothing to guess *(this line said "externalised
  pepper" until `P6-DOC-001`)*.
- The negative tests the phase gate demands ("cross-merchant data access is impossible,
  negative tests per endpoint") have a uniform mechanical shape: authenticate as merchant
  A, address merchant B's resource, assert the one refusal and zero rows touched.
- Webhooks **to** merchants (the platform signing outbound event notifications) reuse the
  `SIGNED_CALLBACK` vocabulary in reverse and are a backlog concern, not a new decision:
  the platform signs with a per-merchant secret exactly as it verifies provider signatures.
- Rate limiting per key remains Phase 15 with the standing per-source limitation row.
