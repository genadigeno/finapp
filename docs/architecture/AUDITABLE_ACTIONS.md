# Auditable Actions

The registry `INV-AUD-01` names as half of its enforcement, and the list the Phase 15 gate
verifies audit completeness against.

**This document and the code are one definition.**
[`AuditableActionRegistryTest`](../../app/src/test/java/com/finapp/app/audit/AuditableActionRegistryTest.java)
fails the build when they disagree in *either* direction — an action declared but not documented,
or documented but no longer declared. Neither list is maintained by hand against the other.

---

## 1. What the registry is, and what it is not

**It is** the set of action types that must produce an audit record. Every implementation of
`AuditableAction` is an enum, so the set is enumerable — which is the only reason a completeness
check is possible at all.

**It is not** a guarantee that every privileged action writes one. Two different claims:

| Claim | Enforced by |
|---|---|
| Every audit record names a *declared* action | The type system. `AuditRecord.operation` is an `AuditableAction`, so an ad-hoc string cannot be recorded |
| Every action that *should* be audited *is* | **Nothing mechanical.** A missing call is a missing call |

The second is what the Phase 15 gate and code review are for. Saying so plainly is more useful
than implying a guarantee the mechanism does not provide — a registry that looked complete while
the calls were missing would be worse than none, because it would be believed.

## 2. Why actions are declared per module

The obvious design — one `AuditAction` enum in the platform listing everything — cannot be built.
Actions belong to the modules that perform them, and the platform sits *below* every business
module (`app -> business modules -> platform -> sharedkernel`). An enum in the platform naming
KYC's actions would invert that dependency and make the platform's vocabulary the union of every
domain's.

So `AuditableAction` is an interface in `platform.audit`, each module declares its own enum, and
only `app` sees all of them — which is where the reconciliation test lives.

**Codes are namespaced by module** (`outbox.EventAbandoned`) so two modules cannot collide on a
bare name and merge two different actions into one line of the trail. The convention is enforced
by the registry test.

**Codes are stable.** Audit records outlive the code that wrote them, and a renamed code makes
historical records refer to an action nobody can look up. Renaming one is a data-migration
question, not a refactor.

## 3. The registry

### `platform` — `PlatformAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `outbox.EventAbandoned` | No | The relay exhausted its attempts for an event and stopped retrying, blocking its aggregate until an operator resolves it. |
| `outbox.EventRetryAuthorised` | **Yes** | An operator cleared an event's abandonment and returned it to the relay's queue. |
| `outbox.EventDiscarded` | **Yes** | An operator accepted that an event will never be published, leaving a permanent gap in what consumers received. |

### `party` — `PartyAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `party.CustomerRegistered` | No | A party was registered and a customer relationship was opened for it. |
| `party.ProfileChanged` | No | A party's own profile data was changed, recording which field and by whom. |

`party.CustomerRegistered` is **one action for two writes**, deliberately. `PHASE_1_PLAN.md` §4
lists `RegisterParty` and `OpenCustomerRelationship` as separate commands, and they are — but
registration performs both atomically and neither is separately reachable, so two records would
describe one decision twice and invite a reader to wonder what it means when only one is present.
It cannot be. It is the **first action on this platform that is actually emitted** (`P1-TSK-006`),
which is why the closing paragraph of this section now says "none of the *platform* actions".

Its target is the attempted **login identifier**, not the Party's own identifier — including on the
refusal path, where no Party exists. That is deliberate: the audit trail is the one place an
attempted identifier may appear (`PHASE_1_PLAN.md` §10), and a record keyed on an identifier that
was never created would be unfindable from the only value anyone will search by if a registration
is later disputed.

The other actions are audited because this module holds personal data, and a change to it changes
what the platform believes about a person — which later decisions, including KYC and credit, are taken against. No
reason is required: this is ordinarily the customer maintaining their own details, and a mandatory
reason on a routine action produces a column of `"update"` (§4). A staff-initiated change on
someone else's behalf is a different action and will be declared when it exists.

### `identity` — `IdentityAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `identity.IdentityCreated` | No | A login was created for a party. |
| `identity.AuthenticationSucceeded` | No | An identity was authenticated. |
| `identity.AuthenticationFailed` | No | An authentication attempt failed. |
| `identity.AuthenticationLocked` | No | An identity was locked after repeated failed authentications. |
| `identity.SessionRevoked` | No | One or more sessions were ended. |
| `identity.SessionRotated` | No | A session was replaced by a new one on a privilege change. |
| `identity.MfaEnrolmentStarted` | No | A second factor enrolment was begun. |
| `identity.MfaEnrolmentConfirmed` | No | A second factor was confirmed and is now usable. |
| `identity.MfaChallengeSucceeded` | No | A second factor was proven and the session was elevated. |
| `identity.MfaChallengeFailed` | No | A second-factor challenge was refused. |
| `identity.AuthorizationDenied` | No | A privileged action was refused because the actor lacked the permission. |
| `identity.IdentitySuspended` | **Yes** | An identity was suspended by an administrator and can no longer authenticate. |
| `identity.IdentityReinstated` | **Yes** | A suspension was lifted by an administrator and the identity can authenticate again. |
| `identity.RoleAssigned` | **Yes** | An administrator changed the roles held by an identity, altering what it is permitted to do. |
| `identity.ContactChannelAdded` | No | A contact channel was registered against an identity, unverified. |
| `identity.ContactChannelVerified` | No | Control of a contact channel was proven, making it usable for account recovery. |
| `identity.RecoveryInitiated` | No | Account recovery was begun for an identity holding a verified channel. |
| `identity.RecoveryCompleted` | No | A credential was replaced through account recovery, ending every session. |
| `identity.CredentialChanged` | No | A person changed their own password; every other session was ended. |

`identity.IdentityCreated` and the two authentication actions require no reason: using your own
login is not an action taken against anybody. The two admin actions below them are, which is the
whole distinction §4 draws.

**`identity.AuthenticationFailed` is recorded for identifiers that do not exist**, and that is the
point rather than an accident (`P1-TSK-010`). A failure rate against identifiers nobody registered
is credential stuffing, and it is invisible if only real accounts are recorded. Its target is the
attempted login identifier - the one place `PHASE_1_PLAN.md` §10 permits an attempted identifier to
appear, because the audit trail is the regulatory artefact and is not client-visible.

**`identity.AuthenticationLocked` requires no reason and has no human actor**, and that is the
distinction §4 draws from the other side: nobody *chose* it. It is the platform reacting to a
pattern, which is exactly why it must be recorded — a lock is the first evidence that somebody is
being attacked, and a spike across many identities is a credential-stuffing campaign in progress
(`P1-TSK-011`). It is written by the attempt that **crosses** the threshold, never by the attempts
afterwards, because repeating it while an account stays locked buries the event under copies of it.

**`identity.SessionRevoked` is recorded once per *operation*, not once per session** (`P1-TSK-014`).
Revoking forty sessions writes one record with the count in its change summary. The division is
deliberate: the session rows carry `revoked_at` and answer *when each one ended*; the audit record
answers *who decided*. Forty records would bury the decision under its consequences.

**Why MFA enrolment is two actions rather than one** (`P1-TSK-017`). *Started* and *confirmed* are
different facts about the account: the first says somebody was offered a secret, the second says
somebody **proved they hold it**, and only the second changes what can authenticate. Recording only
the confirmation would make a started-and-abandoned enrolment invisible — and an enrolment begun on
somebody else's session and never completed is exactly what an account-takeover attempt looks like
from the inside.

**`identity.SessionRotated` is deliberately distinct from `identity.SessionRevoked`**
(`P1-TSK-015`). A rotation revokes the session it replaces, so recording it as a revocation would be
the easy thing — and an investigator would then read a logout that never happened. *"This session was
ended"* and *"this session was replaced"* are different facts, and only the second leaves the person
still logged in. The rotation record names both identifiers so the chain is followable.

**Neither authentication action records *why* a failure failed.** Unknown identity, wrong password,
suspended identity and an identity with no credential are deliberately not distinguished anywhere -
a field that distinguishes them is a field somebody eventually maps to a response, and
`INV-IDN-07` is then lost through the audit trail's own vocabulary.

**Deliberately few.** Authentication, session revocation, credential change and MFA enrolment
are audited too and are *not* declared yet: each belongs to the task that builds it, where
`requiresReason()` can be decided against real behaviour. A registry may list an action before its
code exists; it should not list one before its *design* does.

The two administrative actions require a reason because both are things a human chose to do that the system would not have
done by itself, taken **against someone else's account** — and this is the module where an insider
with a legitimate permission does the most damage. Role assignment is the more consequential of the
two: it is the action by which every other authorization decision can be quietly widened, so an
assignment nobody has to justify is privilege escalation with a clean audit trail.

`INV-AUD-04`'s four-eyes requirement is not yet modelled — `audit_record` records one actor — and
that is recorded debt rather than an omission here.

### `kyc` — `KycAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `kyc.CaseOpened` | No | A KYC/KYB case was opened for a customer, under a named policy version. |
| `kyc.DecisionRecorded` | **Yes** | A KYC/KYB decision was recorded on a case, naming its actor, reason and policy version. |
| `kyc.ScreeningHitResolved` | **Yes** | A reviewer resolved a screening hit, with the resolution and its justification. |
| `kyc.DocumentContentRead` | No | Document content was read, naming who looked and at which document. |

The two reason-required actions are the invariants speaking: `INV-KYC-02` makes a decision's
reason a `NOT NULL` column, and `INV-KYC-04` requires a hit's resolution to carry its
justification — a name match is a probability, and both silent outcomes are unacceptable in
opposite directions. **`kyc.DocumentContentRead` requires no reason, deliberately**: reading a
document is the routine act of every legitimate review, and a mandatory reason on a routine
action produces a column of `"review"` (§4). What `INV-KYC-06` demands is *the trail of who
looked*, and the record names the actor. `kyc.CaseOpened` joined at `P2-TSK-005`, the task
whose design fixed its meaning — no reason, because opening is the customer's own act or the
platform reacting to a registration, neither taken *against* anybody. **Check outcomes remain
absent on purpose** — `P2-TSK-009`'s to declare, the "deliberately few" licence applied as
`party` applied it.

### `consent` — `ConsentAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `consent.ConsentGranted` | No | A party granted consent for a purpose, against a named version of the consent text. |
| `consent.ConsentWithdrawn` | No | A party withdrew consent for a purpose; the gated capability blocks from this record on. |

Neither requires a reason: both are a person's own act, and for withdrawal specifically a
demanded justification would be pressure applied exactly where none may exist. The audit record
and the consent history row are **not the same thing and neither substitutes**: the consent row
is the lawful basis the gate queries (`INV-CNS-01`), the audit record is the trail of the act
(`INV-AUD-01`), and they live under different retention and access regimes.

**The two registration actions are emitted; none of the three `platform` actions is**, and that is
not an oversight. Two describe the manual procedure
in [`EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md) §Handling an abandoned event, performed today
with raw SQL and no audit record at all; the third is a relay decision currently visible only as a
log line, which ADR-0010 is explicit does not count. A completeness registry is precisely the list
that reality is checked *against*, so an action that must be audited belongs here whether or not
the code emitting it exists. The gap is recorded in
[`CURRENT_STATE.md`](../project/CURRENT_STATE.md) §Known Architectural Debt.

**`P1-TSK-022` makes that distinction mechanical.** `AuditableActionRegistryTest` reconciles this
document with the code and states its own limit — *it cannot detect a privileged action that writes
no record at all*, which is the failure that matters, because a registry agreeing with a catalogue
while nothing emits half of it looks complete from both sides. `AuditCompletenessTest` now holds
every action against production code: each is **emitted**, or **declared not to be** with the task
that will emit it. So *"deliberately not built yet"* and *"somebody removed the audit call"* stop
being indistinguishable, and an action that silently **stops** being emitted fails the build.

Three actions are currently declared unemitted: the three `outbox.*` actions above.
`identity.IdentitySuspended` left the list at `P1-TSK-028` and `party.ProfileChanged` at
`P1-TSK-030`, each when the endpoint that emits it was built - which is the list working in the
direction it is rarely exercised in, since an entry is a claim about the future and the future
arriving is what removes it. Its limit is stated too: it sees that a constant is *referenced* by production code,
which is weaker than *the operation audits itself* — the behavioural tests establish that, and
neither replaces the other.

## 4. When an action requires a reason

`V009` made `reason` nullable and deferred the decision: *"for the actions that do, absence is not
permitted, and the domain decides which those are."* The registry is where the domain decides, and
`AuditRecord` enforces it — so the answer is attached to the action rather than remembered at each
call site.

Require a reason for anything **a human chose to do that the system would not have done by
itself**: an override, a manual correction, a discretionary refusal. `INV-REV-04` requires it for
adjustments, and the same reasoning covers any privileged action whose justification is the only
evidence it was legitimate.

Do not require one for actions a rule took automatically. A mandatory reason on an automatic action
produces a column of `"automatic"`, which is how a required field stops meaning anything.

## 5. Adding an action

1. Add the constant to the owning module's `AuditableAction` enum, with a namespaced code, a
   description an auditor can assess, and a considered answer for `requiresReason()`.
2. Add the row to §3 above.
3. Emit it from the code that performs the action, in the same transaction (ADR-0010).

Steps 1 and 2 are checked against each other by the build. **Step 3 is not**, and cannot be — see
§1.
