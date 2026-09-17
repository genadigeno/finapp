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
| `party.OrganisationRegistered` | No | An organisation was registered, with the acting person recorded as its registrant. |
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
| `kyc.ReviewResolved` | **Yes** | A reviewer resolved a review task — the judgement a non-clean check owed a person — with its justification. |
| `kyc.CheckCompleted` | No | A verification check reached its outcome — the platform recording a provider's answer in its own vocabulary. |
| `kyc.DocumentContentRead` | No | Document content was read, naming who looked and at which document. |
| `kyc.CaseRead` | No | A reviewer read a KYC/KYB case, naming who looked and at which case. |
| `kyc.OwnerDeclared` | No | A beneficial owner was declared onto a KYB case, pinning the verification case the graph rests on. |

The two reason-required actions are the invariants speaking: `INV-KYC-02` makes a decision's
reason a `NOT NULL` column, and `INV-KYC-04` requires a hit's resolution to carry its
justification — a name match is a probability, and both silent outcomes are unacceptable in
opposite directions. *(`kyc.ReviewResolved` was catalogued as `kyc.ScreeningHitResolved` until
`P2-TSK-012` — renamed before its first emission, because a task is raised by any check type's
`HIT` and by a budget-exhausted `INDETERMINATE`, so the old code claimed something that can be
false; a vocabulary correction is free exactly while zero records carry the code.)*
**`kyc.DocumentContentRead` requires no reason, deliberately**: reading a
document is the routine act of every legitimate review, and a mandatory reason on a routine
action produces a column of `"review"` (§4). What `INV-KYC-06` demands is *the trail of who
looked*, and the record names the actor. `kyc.CaseRead` (`P2-TSK-012`) is the same argument one
level up: the reviewer is the platform's canonical insider surface, and the trail of who looked
at a case is the control on the person with every right to look. `kyc.CaseOpened` joined at
`P2-TSK-005`, the task
whose design fixed its meaning — no reason, because opening is the customer's own act or the
platform reacting to a registration, neither taken *against* anybody. `kyc.CheckCompleted`
joined at `P2-TSK-009` and requires no reason for the same shape of argument: recording an
outcome is the platform normalising a provider's answer, an act taken *for* nobody and
*against* nobody — the acts that demand justification are the decision and the review
resolution, which are the two reason-required rows above. It is deliberately **not** a
decision record (`INV-KYC-01`: a provider verdict is evidence, never the decision).
`kyc.OwnerDeclared` joined at `P2-TSK-015`: the declaration changes what a regulator-facing
decision will rest on (`INV-KYC-02` — the owner set is part of the decision's evidence), which
is what makes it an action of consequence; no reason, because declaring the graph is the
declarant's own compliance act, taken for and against nobody — the consent-pair argument.

`party.OrganisationRegistered` joined at `P2-TSK-016`, and its actor is **the proven person,
never the platform** — the opposite of `party.CustomerRegistered`, whose caller is
unauthenticated. One record for three writes (the ORGANISATION party, its customer, the
registrant row), on `party.CustomerRegistered`'s one-decision reasoning; the change summary
carries the registrant linkage because *who may act for this organisation* is the record's
point. No reason: registering one's own organisation is not an action taken against anybody.

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

### `ledger` — `LedgerAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `ledger.JournalEntryPosted` | No | A balanced journal entry was posted to the ledger; the record names the entry, never an amount. |
| `ledger.AdjustmentPosted` | **Yes** | A person posted a manual adjusting entry; the reason and the authorising actor are recorded. |
| `ledger.AdjustmentProposed` | **Yes** | A person proposed a manual adjustment for a second person's approval; the reason and the initiator are recorded. |
| `ledger.AdjustmentRejected` | No | A standing adjustment proposal was rejected or withdrawn; the record names the proposal and who declined it. |
| `ledger.HoldPlaced` | No | A hold was placed against an account's available balance; the record names the hold and the account, never an amount. |
| `ledger.HoldReleased` | No | A standing hold was released, restoring available balance; the record names the hold and the account, never an amount. |

**A reversal is deliberately not its own action** (`P3-TSK-016`): the act is *a journal
entry was posted*, and `ledger.JournalEntryPosted`'s record and event already carry the
entry's kind (`entryType`) as data, with the reversal's own distinguishing fact — which
original it compensates — on the entry row (`reverses_entry_id`, `INV-REV-01`) where an
investigator joins it. A second action would name one fact twice, and the two names would
drift.

The posting and adjustment pair were declared with the module skeleton (`P3-TSK-001`) under
the deliberately-few licence — named outright by `PHASE_3_PLAN.md` §11, so their design was
fixed — while holds, reversals and account creation were left to the tasks whose designs would
shape them. **The hold pair arrived exactly that way** (`P3-TSK-015`): no reason, on the
posting's own argument — a hold is commanded by a platform flow whose records carry the why —
each code shared with the event the same act publishes, emitted only by the acting call
(`HoldService`), so a converged release records nothing (`INV-KYC-03`'s discipline). A posting needs no reason
because it is commanded by a flow whose own records carry the why; an adjustment requires one
because `INV-REV-04` says so in as many words, and because a human choosing to move value the
system would not have moved is the one act whose justification is its only evidence of
legitimacy. **Four-eyes is two acts and two records** (`INV-AUD-04`, `P3-TSK-021`):
`ledger.AdjustmentProposed` names the initiator with the justification at the moment they
wrote it, and `ledger.AdjustmentPosted` names the *approver* — a second person, enforced at
the domain and at `DB-CONSTRAINT` (`V010`). No record carries two actors, because both
people acted, each on their own record — which is how ADR-0010's anticipated "second actor
column" dissolves rather than gets paid. `ledger.AdjustmentRejected` requires no reason:
declining to move value is not the high-risk act the reason regime exists for, and a
mandatory justification for saying no would produce a column of "no"; the record names who
declined, which is the fact an investigator wants. *(This paragraph called four-eyes
"recorded debt" until `P3-TSK-021` built it.)*
`ledger.JournalEntryPosted` shares its code with the event the same posting publishes, as
`identity.AuthenticationSucceeded` does: one fact, named once, in two registries. Both are
emitted — `ledger.JournalEntryPosted` by `P3-TSK-006`'s posting command (and by the reversal,
whose kind travels as data), `ledger.AdjustmentPosted` by the approval of a proposal (`P3-TSK-017`, four-eyes since
`P3-TSK-021`),
each in its own transaction, with `PostingEffect` deriving the action from the entry's kind
so the adjustment's reason regime cannot be skipped by a careless caller.

### `accounts` — `AccountsAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `accounts.AccountOpened` | No | A customer account product was opened; the record names the account, the product type and the customer, never a balance. |
| `accounts.AccountClosed` | No | A customer account product was closed; the agreement ended, the accounting history did not (INV-HIST-01). |

Declared with the aggregate whose design fixes its meaning (`P3-TSK-012`) rather than with the
module skeleton — `P3-TSK-011`'s recorded decision, the `kyc.CaseOpened`/`P2-TSK-005`
precedent. No reason: opening is a person's own act on their own relationship (the consent-action
reasoning, §4), and a mandatory justification would produce a column of *"wanted an account"*.
The code matches the event type the same opening publishes — one fact, named once, in two
registries — and is emitted by `AccountOpening` in the opening transaction, by the
<strong>creating</strong> call only: a converged retry is not a second act.
`accounts.AccountClosed` arrived with `P3-TSK-014` — the task whose design fixed it, exactly
as the absence recorded here predicted. No reason, for the withdrawal's own argument: closing is
a person's exit from their own agreement, and a demanded justification at that moment is
pressure applied where none may exist; the control is the zero-balance precondition. Emitted by
the closing call only — a converged repeat is not a second act. Suspension still has no
producer this phase.

### `transfers` — `TransfersAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `transfers.TransferExecuted` | No | A transfer execution was judged: COMPLETED with its posting or FAILED with its enumerated reason; the record names the transfer, the accounts and the outcome, never an amount. |
| `transfers.BeneficiaryAdded` | No | A party saved a transfer destination; the record names the beneficiary and the destination account by identifier, never the display name. |
| `transfers.BeneficiaryRemoved` | No | A party removed a saved transfer destination; the removed row survives as evidence. |

Declared with the command whose design fixes its meaning (`P4-TSK-005`, `P4-TSK-007`) rather
than with the module skeleton — `P4-TSK-001`'s recorded decision, the `accounts`/`P3-TSK-011`
precedent one phase over. **One record per execution, whatever the judgement**: a `FAILED`
transfer is a committed domain outcome (ADR-0043/0044), and its "why" is the enumerated
`FailureReason` on the row itself, so no free-prose reason is demanded — executing a transfer
is a person's own act with their own money (the `accounts.AccountOpened` reasoning). Emitted by
`TransferExecution` in the execution transaction, by the executing call only: a replayed retry
is not a second act. **The beneficiary pair are a person's own acts** (`P4-TSK-007`) — no
reason, the consent pair's reasoning — emitted by the acting call only (a converged create or
removal moved nothing and records nothing), with the display name (`RESTRICTED-PII`) never in
target or summary: `transfers.BeneficiaryAdded` is the trail creating a destination leaves,
which is the act where an account takeover monetises and the reason the surface demands the
enrolled identity's second factor. The reversal's action, with its **required** reason and its
privileged actor, is `P4-TSK-009`'s design and deliberately not declared here.

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
`ledger.AdjustmentPosted` left the list at `P3-TSK-017`, when the adjustment endpoint was
built *(this sentence still counted it as waiting until `P3-TSK-021` — the doc went stale
when the code moved, corrected in passing)*; the four-eyes pair arrived emitted
(`P3-TSK-021`).
`ledger.JournalEntryPosted` left the list at `P3-TSK-006`, when the posting command was built.
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
