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
| `party.ProfileChanged` | No | A party's profile data was changed, recording what was held before and after. |

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
| `identity.IdentitySuspended` | **Yes** | An identity was suspended by an administrator and can no longer authenticate. |
| `identity.RoleAssigned` | **Yes** | An administrator changed the roles held by an identity, altering what it is permitted to do. |

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

**The two registration actions are emitted; none of the three `platform` actions is**, and that is
not an oversight. Two describe the manual procedure
in [`EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md) §Handling an abandoned event, performed today
with raw SQL and no audit record at all; the third is a relay decision currently visible only as a
log line, which ADR-0010 is explicit does not count. A completeness registry is precisely the list
that reality is checked *against*, so an action that must be audited belongs here whether or not
the code emitting it exists. The gap is recorded in
[`CURRENT_STATE.md`](../project/CURRENT_STATE.md) §Known Architectural Debt.

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
