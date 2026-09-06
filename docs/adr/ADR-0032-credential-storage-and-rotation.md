# ADR-0032 — Credentials store a derivation and the parameters that produced it

Status: Proposed

Date: 2026-09-03

## Context

`DELIVERY_PLAN.md` §6 requires "password hashing algorithm and parameters recorded per credential
for rotation" and §9 names Argon2id. §17 names the risk: *"rolling bespoke cryptography"*.

The decision that is usually missed is not which algorithm. It is **where the parameters live**.

A platform that configures its work factor globally has no way to increase it. Raising the setting
does not change stored credentials; it changes what new ones use. The store then holds a mix of
strengths that nothing records, and the only ways out are to invalidate every credential — which
means a forced reset for every customer — or to guess. This is the same failure `INV-HIST-04`
catalogues for policy versions: a decision that cannot be reproduced cannot be defended, and here
the decision is *how strongly was this credential protected?*

## Decision

**A credential row stores the derivation, the algorithm, and the parameters that produced it.**

| Column | Why |
|---|---|
| `derivation` | The output. Never the secret, never reversible (`INV-IDN-01`) |
| `algorithm` | Named and versioned, so a second algorithm can coexist during migration |
| `parameters` | The cost factors this specific derivation used (`INV-IDN-02`) |
| `created_at`, `rotated_at` | Lifecycle, and the input to a "credentials older than X" report |

**Argon2id**, via a vetted library. No custom primitive, no hand-rolled comparison, no bespoke
salting scheme. Parameters are chosen against a stated target verification time, recorded, and
reviewed rather than set once and forgotten.

**Upgrade on successful use.** When a credential verifies against parameters weaker than current
policy, it is re-derived with current parameters inside the same transaction. This is the only
moment the platform legitimately holds the plaintext, and it is the only moment an upgrade is
possible without involving the customer. Over time the store converges with no forced reset and no
customer-visible event.

**Verification is constant-time**, and a non-existent identity performs a dummy verification of
equivalent cost. Skipping the work when there is no credential turns response time into an account
oracle, which is `INV-IDN-07` lost through the timing channel rather than the response body.

**A credential is never logged, echoed, emitted or included in an event.** `INV-AUD-02` and the
`secretsAreWrapped` build rule already enforce the field-and-accessor half; this ADR adds that
`identity`'s events carry no credential material at all, which `MODULE_ARCHITECTURE.md` already
states and which is now attached to an invariant.

**Changing a credential revokes sessions.** A credential change is either the customer securing
their account or an attacker consolidating access; both cases require every other session to end
(ADR-0030). The exception is the session performing the change, which is rotated rather than
revoked.

## Alternatives Considered

### Option A — Global work-factor configuration
Pros: One setting; simplest to implement; what most tutorials show.
Cons: The store silently becomes a mix of strengths with no record of which is which. Increasing
the factor protects only new credentials, and there is no way to find or upgrade the old ones
because nothing recorded what they used. Rejected on `INV-IDN-02`.

### Option B — Parameters encoded in the derivation string (PHC format)
Pros: One column; the standard encoding; the library round-trips it; genuinely good practice.
Cons: The parameters are then inside an opaque string that only the hashing library can read, so
*"how many credentials are below current policy?"* becomes a full table scan with a parse per row
rather than an indexed query. For a platform that must be able to report on and drive an upgrade
campaign, that is the wrong shape. **Partially adopted**: the library's encoded form is stored as
the derivation, and the algorithm and parameters are *additionally* stored as queryable columns.
The duplication is deliberate and the encoded form remains authoritative for verification.

### Option C — Delegate to an external identity provider
Pros: Someone else's problem; strong defaults; no credential storage at all.
Cons: `DECISIONS.md` §Deliberately Deferred keeps real external connectivity out of the platform,
and the purpose of Phase 1 is to build this understanding rather than to procure it. It would also
make Phase 1 an integration exercise and leave the platform unable to demonstrate the invariants
this catalogue now carries.

### Option D — Derivation plus queryable algorithm and parameters (chosen)
Pros: `INV-IDN-02` holds. An upgrade campaign is an indexed query. Two algorithms can coexist while
migrating. Upgrade-on-use converges the store without a forced reset.
Cons: Three columns where one would do, and one duplicated fact. Accepted: the duplication is
between a form optimised for verification and a form optimised for reporting, and the authoritative
one is named.

## Consequences

Positive:
- The work factor can be raised without invalidating a single credential.
- *"How strongly was this credential protected?"* is answerable per credential, permanently.
- Credential material has no path into logs, events or responses that is not already a build
  failure.

Negative:
- Verification is deliberately expensive, which makes the login endpoint the platform's most
  CPU-costly operation and a denial-of-service target. Rate limiting and lockout are therefore not
  optional extras but part of the same design (`P1-TSK-011`), and pool sizing must account for
  requests that hold a connection while doing CPU work.
- Upgrade-on-use writes on a read-shaped path. It is one `UPDATE` inside the verification
  transaction, on a row already located.

## Invariants / Constraints

`INV-IDN-01`, `INV-IDN-02`, `INV-IDN-07`, `INV-AUD-02`, `INV-HIST-04` (the reasoning this applies
outside policy versions). ADR-0030 (session revocation on change), ADR-0022 (the derivation column
is classified at its ceiling before it holds anything).

## Follow-up

### Implemented by `P1-TSK-007` (2026-09-06)

**Measured cost, because "a stated verification time" that nobody measured is not stated.** At the
shipped parameters — **m = 19456 KiB, t = 2, p = 1**, OWASP's Argon2id baseline — one derivation
takes **~46 ms** on the development machine, asserted continuously by a floor rather than a ceiling
(a ceiling is a flaky test on a loaded machine; a derivation completing in under a millisecond is
the failure actually worth catching). 46 ms is at the fast end of the usual interactive-login
target. It is **not** raised here, deliberately: raising the work factor is a capacity decision that
belongs beside the rate limiting this ADR already names as part of the same design (`P1-TSK-011`),
and a per-derivation cost of 19 MiB means ten concurrent logins on one instance is ~190 MiB of
transient allocation. Raising it is now a one-line edit to `DerivationParameters.current()`, pinned
by a test so it cannot drift silently.

**`INV-IDN-01` is enforced at `DB-CONSTRAINT`, not only at `DOMAIN` and `STATIC`.** The derivation
column will not accept a value that is not in its algorithm's encoded form, so **a plaintext
password cannot physically be stored** — not by a migration, an operator, or code nobody has written
yet. That is stronger than this ADR asked for and is the right place for the platform's most
consequential secret.

**The library needs more than its POM declares, and that was found by running it.**
`spring-security-crypto` 7.1.1 lists exactly one dependency: an *optional* assertj. In fact it needs
**BouncyCastle** to derive and **spring-core** to verify, each surfacing as a separate
`NoClassDefFoundError` from a test that used the real encoder — one at construction, one at
`matches`. A test double would have found neither and the failure would have arrived at the first
real login. It follows that `identity` does take a Spring Framework runtime dependency, which is
recorded plainly rather than described away.

### Verification and upgrade-on-use, by `P1-TSK-008` (2026-09-06)

**The store converges with no forced reset**, proven rather than argued: a credential written under
weaker parameters verifies, is re-derived at current policy inside the same transaction, and the
same password works afterwards. The customer notices nothing.

**Every failing path performs a full verification.** Four ways to fail - no identity, an identity
that cannot authenticate, an identity with no credential, and a wrong password - and all four run
Argon2id against a throwaway derivation computed once at construction. The middle two are the ones an
implementation skips, and a suspended account answering instantly tells an attacker both that it
exists and that it is suspended. **Asserted by counting derivations rather than by reading a clock**:
a wall-clock test is flaky and measures the machine, while a counting deriver measures the property.

**One residual, stated.** The dummy runs at *current* parameters and a real credential may be at
weaker ones, so verifying a stale credential is genuinely cheaper than failing. What bounds it is
this ADR's own upgrade-on-use: the store converges and the gap closes itself.

**An upgrade failure never fails a correct authentication.** The customer typed the right thing, so
the upgrade sits behind a savepoint and any failure of it is discarded. Refusing a valid login
because a background optimisation collided would be a self-inflicted outage.

**Deferred, with the owning task named.** Constant-time *response shaping* and the timing of the
endpoint as a whole are `P1-TSK-010`; lockout is `P1-TSK-011`; session revocation on credential
change is M1.3.

**Deferred within this ADR, with the owning task named.** The dummy verification for an absent
identity is now built (`P1-TSK-008`). What remains is the endpoint that calls it.



- WebAuthn/passkey credentials are a different credential *type* in the same aggregate, not a
  different table: they share the lifecycle and differ in what verification means. Phase 1,
  milestone M1.4.
- Phase 15 owns credential-rotation policy as an operational process, and the "older than X" report
  this schema makes possible.
