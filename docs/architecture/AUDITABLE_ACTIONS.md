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
| `transfers.TransferExecuted` | No | A transfer execution was judged: COMPLETED with its posting or FAILED with its enumerated reason; the record names the transfer, its status, the failure reason or the journal entry, never an amount - the accounts are the transfer row's, which the target names (this said the record names the accounts until the Phase 6 → 7 transition; it never did). |
| `transfers.BeneficiaryAdded` | No | A party saved a transfer destination; the record names the beneficiary and the destination account by identifier, never the display name. |
| `transfers.BeneficiaryRemoved` | No | A party removed a saved transfer destination; the removed row survives as evidence. |
| `transfers.TransferReversed` | **Yes** | An operator reversed a completed transfer with a recorded reason; the record names the transfer, the original entry and the reversal entry, never an amount. |

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
enrolled identity's second factor. **`transfers.TransferReversed` is the module's one
reason-required action** (`P4-TSK-009`, `PHASE_4_PLAN.md` §11): a reversal is a privileged act
over somebody else's money, so the operator's justification enters the trail at the moment they
write it — made structurally mandatory by `AuditRecord`'s constructor, carried in a `POST` body
rather than a query parameter because free prose may name a person or an incident
(`INV-AUD-02`). Emitted by `TransferReversal` in the reversal transaction, by the winning move
only: the loser of a concurrent race writes nothing at all, and the ledger's own
`ledger.JournalEntryPosted` record of the reversal entry sits beside it — two acts at two
levels, the `P4-TSK-005` layering.

### `paymentmethods` — `PaymentmethodsAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `paymentmethods.PaymentMethodAttached` | No | A party attached a payment instrument — a card token, or a bank account through the grant exchange (`P7-TSK-007`); the record names the payment method by identifier, never a reference or the display metadata. When the customer's `NO_MATCH` acknowledgement was the gate, the reason carries the enumerated constant `PAYEE_CHECK_NO_MATCH_ACKNOWLEDGED` — a consent fact, never a value (ADR-0062 §2). |
| `paymentmethods.PaymentMethodDetached` | No | A party detached a payment instrument; the detached row survives as evidence. |

Declared with the surface whose design fixes their meaning (`P5-TSK-005`) rather than with the
module skeleton — `P5-TSK-001`'s recorded decision, the `transfers` precedent one phase over.
**Both are a person's own acts** — no reason, the beneficiary pair's reasoning — emitted by the
acting call only (a converged attach or detach moved nothing and records nothing), with the
token and the display metadata (`RESTRICTED-PII` at the register) never in target or summary:
`paymentmethods.PaymentMethodAttached` is the trail attaching an instrument leaves, which is
the act where an account takeover monetises and the reason the surface demands the enrolled
identity's second factor (`INV-PAY-02`'s surface, `P4-TSK-007`'s step-up verbatim).

### `payments` — `PaymentsAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `payments.PaymentIntentCreated` | No | A person created a payment intent; the record names the intent, the instrument and the wallet account by identifier, never an amount. |
| `payments.PaymentConfirmed` | No | A person confirmed a payment intent; the dispatch committed before the provider call, and the record names the intent, the attempt and — since `P7-TSK-001` — the rail the dispatch decided, never an amount. |
| `payments.PaymentCancelled` | No | A person cancelled a payment intent before confirmation - or the platform cancelled one a checkout session opened and never dispatched, as the session expired (the Phase 7 → 8 transition; the actor `SYSTEM`, the cause as its reason); nothing was dispatched and nothing was posted. |
| `payments.PaymentCaptureDispatched` | No | The platform dispatched a capture for an authorized attempt; the reference was stored before the provider was asked, and the record names the attempt, the intent and — since `P7-TSK-001` — the attempt's stored rail, never an amount. |
| `payments.PaymentOutcomeApplied` | No | The platform applied a provider outcome to a dispatched payment operation through a conditional transition; the record names the operation and the committed states, never an amount or a provider code. Written only on an acting transition: a resolver that lost the race records nothing (since the Phase 6 → 7 transition; before, every loser wrote one). |
| `payments.PaymentVoidDispatched` | No | A customer cancelled their authorized payment, or an operator voided it with a reason recorded verbatim; the promise was released at the provider, and the record names the intent, the attempt, the minted void reference and the stored rail, never an amount. |
| `payments.WithdrawalDispatched` | No | A customer dispatched a wallet withdrawal (`P7-TSK-008`): judged under the wallet's lock, held, routed and committed with our minted reference before the scheme is asked; the record names the withdrawal, the wallet account, the instrument and the rail, never an amount. |
| `payments.WithdrawalOutcomeApplied` | No | The scheme's word landed on a withdrawal — applied by the dispatching flight, a takeover or the inquiry sweep, the platform's act on the locked row, acting once; the record names the withdrawal, the status, the failure class and the resolver, never an amount or a reference. |
| `payments.UnmatchedConfirmationParked` | No | The platform parked a money-carrying confirmation that named no initiation it made (`P7-TSK-009`, ADR-0062 §5, `INV-REC-05`): value moved on the rail with no commercial home, so it rests in `SUSPENSE_UNMATCHED` — aged, alerted, never credited by guesswork; acting insert only, a duplicate delivery converges and records nothing; the record names the rail and the suspense entry, never an amount. |
| `payments.PaymentRefundDispatched` | **Yes** | An operator dispatched a bounded refund of a captured payment, with the required reason; the record names the refund, the attempt and the intent, never an amount. |
| `payments.PaymentRoutingVersionCreated` | **Yes** | An operator created an immutable routing policy version, effective forward, with the required reason; the record names the version number and rule count, never a ceiling amount. |
| `payments.RailAvailabilityChanged` | **Yes** | An operator recorded a rail as available or out of service, with the required reason; the record names the rail and the new state. |
| `payments.PaymentRoutingRefused` | No | A payment was refused because no declared rail could carry it; the record names the intent, the decision, the pinned policy version and the step count, never an amount. |
| `payments.PaymentRoutingExplanationRead` | No | An operator read a payment's routing explanation; the record names the intent and the decision. |
| `payments.DisputeStageApplied` | No | The platform applied a dispute stage the card PSP notified (`P7-TSK-012`, ADR-0061 §6) — opening the dispute at its entry stage, or moving it along one edge of its machine; one record per stage applied, so a later stage's intervening ones each stand on the record, in order. As the platform, through the webhook door's enumerated `enterSystem()` site; acting only — a duplicate, late or contradicting delivery moves nothing and records nothing. The record names the dispute, the attempt and the stages as the platform's own names, never an amount or a provider code. |
| `payments.DisputeRead` | No | An operator read a dispute under `DISPUTE_ADMINISTER` (`P7-TSK-012`) — somebody else's contested payment, its reason and its amount; one record per dispute shown, a refusal records nothing. The record names the dispute and its attempt. |
| `payments.ChargebackReattributed` | No | The platform moved part of a standing chargeback's excess back to the payment's counterparty (`P7-TSK-013`, ADR-0061 §3): a capture LANDED on an attempt the chargeback was already stated against, a refund the chargeback had counted as non-failed FAILED (its money never went back), or a sibling chargeback was WON (its attribution reversed) — each freeing headroom under the combined bound. Under the attempt lock, in the freeing transaction, as the platform (the resolver's or the webhook door's enumerated `enterSystem()` site); one record per dispute moved, beside the `dispute-reattribution:<dispute>:<cause>` entry that references the dispute. The record names the dispute, the attempt, the cause and whether the share landed on the counterparty or was parked — never an amount. |
| `payments.DisputeFeeRecorded` | No | The platform recorded the dispute fee the card PSP reported (`P7-TSK-013`, ADR-0061 §4) — once per dispute, with the chargeback's statement or reported later — and posted it `DR DISPUTE_COSTS / CR` the rail's clearing under `dispute-fee:<id>`; the platform bears it in Phase 7. As the platform, through the webhook door's enumerated site. The record names the dispute and its attempt, never an amount. |
| `payments.DisputeEvidenceUploaded` | No | A responder attached a document to a dispute (`P7-TSK-014`, ADR-0061 section 7, `INV-DSP-03`): the merchant over its key, or an operator for a payment with no merchant with its reason in the record's reason field - encrypted under the dispute-evidence key before it is stored. A re-upload of the same bytes converges on the one document and says `created=false`. The record names the dispute, the document, its kind, format and size, never its content. |
| `payments.DisputeEvidenceRead` | No | Somebody read a dispute evidence document's content (`P7-TSK-014`, `INV-DSP-03` - `INV-KYC-06`'s regime restated): the merchant within its tenancy or an operator under `DISPUTE_ADMINISTER`, committed in the read's own transaction; a read of a document that does not exist records nothing. The record names the document and its dispute. |
| `payments.DisputeEvidenceTransmitted` | No | Evidence content left for the card PSP with a representment (`P7-TSK-014`): every wire send - the dispatching flight's, as the responder, and every re-send a takeover or the resolution sweep makes, as the platform - committed before the bytes are sent. The record names the response, its dispute and the documents sent, never their content. |
| `payments.DisputeResponseDispatched` | No | A responder answered a chargeback (`P7-TSK-014`, ADR-0061 section 7): a representment or an acceptance, judged under the attempt and dispute locks and committed with our minted reference before the PSP is asked (`INV-PAY-04`) - the merchant's act over its key, or an operator's for a payment with no merchant with its reason. The record names the dispute, the response, its kind, the documents it carries and our reference. |
| `payments.DisputeResponseOutcomeApplied` | No | The PSP's word landed on a dispute response (`P7-TSK-014`): applied by the dispatching flight, a takeover or the resolution sweep - the platform's act whichever wins, on the locked row, acting once. `SUBMITTED` means the PSP took the answer; the dispute's stage stays the network's word. The record names the response, its dispute, the status and the failure class. |
| `payments.ChargebackRatioRead` | No | An operator read the chargeback-ratio report under `MERCHANT_ADMINISTER` (`P7-TSK-015`; `PHASE_7_PLAN.md` §15, ADR-0018's report-not-tag rule): every merchant's card sales and chargebacks for one calendar month, across tenants, the standing judgement's evidence. One record per report served, committed with the read or neither happens; a refused period records nothing. The record names the period (its target) and how many credited accounts it counted - never a merchant, never an amount. |

Declared with the commands whose designs fix their meaning (`P5-TSK-009`; the capture's
dispatch action arrived with its command, `P5-TSK-010`) — exactly as the module's
`package-info` licence promised; the refund's arrives with `P5-TSK-015`, the phase's one
reason-required action. The first three are a person's own acts with their own money (the
`transfers.TransferExecuted` reasoning); **`PaymentCaptureDispatched` and
`PaymentOutcomeApplied` are the platform's** — enumerated `enterSystem()` sites, because the
continuation of a confirmed intent and a provider's answer both have no session
(`PHASE_5_PLAN.md` §11; ADR-0046 §1 requires the initiation's record, and capture's initiator
is the platform where the authorization's dispatch rode the person's `PaymentConfirmed`).
Summaries carry identifiers, verdicts and committed states as enumerated names, never provider
vocabulary (`INV-PAY-03`) and never an amount (`INV-AUD-02`). Each is emitted by the acting
call only: an idempotent replay, a converging retry and a losing racer moved nothing and
record nothing.

### `merchant` — `MerchantAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `merchant.MerchantOnboarded` | No | An operator onboarded a merchant; the record names the merchant, its organisation party and its settlement currency by identifier - the payable is found from those two, owner-scoped (this said it named the payable ledger account until the Phase 6 → 7 transition; it never did). |
| `merchant.MerchantSuspended` | **Yes** | An operator suspended a merchant - new dispatches refuse, landed money still lands; the reason is required. |
| `merchant.MerchantReinstated` | **Yes** | An operator reinstated a suspended merchant; the reason is required. |
| `merchant.MerchantClosed` | **Yes** | An operator closed a merchant - terminal; the payable position and its history remain; the reason is required. |
| `merchant.MerchantApiKeyIssued` | No | An operator issued an API key to a merchant; the record names the key by its public id and the merchant by identifier, never the secret. |
| `merchant.MerchantApiKeyRevoked` | **Yes** | An operator revoked a merchant's API key - terminal, never reinstated; the reason is required. |
| `merchant.FeeScheduleCreated` | No | An operator created a fee schedule; the record names the schedule by identifier with its name and currency. |
| `merchant.FeeScheduleVersionCreated` | **Yes** | An operator created a fee schedule version - immutable, effective forward; the record names the version by identifier with its terms; the reason is required. |
| `merchant.MerchantFeeScheduleAssigned` | **Yes** | An operator assigned a merchant to a fee schedule; the record names the merchant and both schedules by identifier; the reason is required. |
| `merchant.PayoutDestinationProposed` | **Yes** | An operator proposed a payout destination for a merchant; the record names the destination and the merchant by identifier, never the bank reference; the reason is required. |
| `merchant.PayoutDestinationApproved` | **Yes** | A second operator, distinct from the proposer, approved a payout destination and its cooling-off started; the reason is required. |
| `merchant.PayoutDestinationApprovalRefused` | **Yes** | The proposer of a payout destination tried to approve it and was refused (INV-AUD-04); recorded as DENIED with the attempted reason. |
| `merchant.PayoutDestinationRejected` | **Yes** | An operator rejected a proposed payout destination - terminal; the reason is required. |
| `merchant.PayoutDestinationWithdrawn` | **Yes** | An operator withdrew a payout destination change before it took effect - terminal; the reason is required. |
| `merchant.PayoutDestinationEffective` | No | The platform made an approved payout destination effective once its cooling-off elapsed, superseding the previous one in the same transaction; the record names both by identifier. |
| `merchant.MerchantPayoutInitiated` | No | A merchant initiated a payout of its payable with its API key; the record names the payout, the destination and the key by identifier. |
| `merchant.MerchantPayoutInitiatedByOperator` | **Yes** | An operator initiated a payout of a merchant's payable on its behalf; the reason is required. |
| `merchant.MerchantPayoutOutcomeApplied` | No | The platform applied the payout provider's answer to a payout (completed, failed or unknown); acting transitions only. |
| `merchant.PayoutReturnApplied` | No | The platform applied a payout return from settlement evidence: the payable credited back by the return's own posting, the payout still COMPLETED. |

The three pricing actions arrive with `P6-TSK-004`, and their reason split is the
`MerchantApiKeyIssued`/`Revoked` split restated: **creating a named schedule needs no reason**
— a container carries no price — while **setting what the platform charges does**, because a
version can never be edited, only superseded, and an unexplained price change is precisely
what a reviewer reading a disputed merchant statement needs explained (`INV-AUD-03`).
`MerchantFeeScheduleAssigned` is emitted by the **moving** call only: an assignment that
converges on the schedule the merchant is already on changed nothing, and records nothing.

**Five of the six payout destination actions require a reason, and the sixth is the
platform's** (`P6-TSK-011`, ADR-0056). Proposing, approving, rejecting and withdrawing are each an
operator's judgement about where a counterparty's money goes, and the trail must be able to say
why. `merchant.PayoutDestinationApprovalRefused` is the refused self-approval, recorded as
`DENIED` in a transaction that commits nothing else — it keeps the attempted reason, the only
record of what the refused actor said they were doing. `merchant.PayoutDestinationEffective`
needs none: nobody decided anything when the cooling-off elapsed, and the decisions are already
on the trail as the proposal and the approval. No record ever carries the provider reference or
its suffix (`INV-AUD-02`).

**One of the three payout actions requires a reason, and it is the operator's**
(`P6-TSK-012`, ADR-0057). A merchant paying out its own payable with its own key is doing the
ordinary thing the surface exists for, and the record names the key that acted — the first
merchant act to carry ADR-0052 §2's key id; the checkout session's records carry it too since
the Phase 6 review (`P6-DOC-001`), which this sentence denied until the Phase 6 → 7 transition. An
operator moving a merchant's money on its behalf is a judgement the trail must explain, so
`merchant.MerchantPayoutInitiatedByOperator` requires it, and the payout row keeps it too.
`merchant.MerchantPayoutOutcomeApplied` is the platform's: the dispatch's own outcome
transaction or the resolution sweep, through enumerated `enterSystem()` sites, written only on
an acting transition — so ten racing resolvers leave one record per move, not ten. No record
carries the destination's provider reference (`INV-AUD-02`). The ledger's own hold and posting
records sit beside these, as they do for the refund. `merchant.PayoutReturnApplied`
(`P8-TSK-019`, ADR-0073 §4) is the platform's too: the return worker's enumerated `enterSystem()`
site applies a return the beneficiary bank made, from the payout provider's own evidence, written
only when it applies — a refusal (the payout not completed, another amount, the payable closed)
and a losing racer write nothing, so ten workers on one return leave one record. When a return
cannot apply, the person who later transfers its value is recorded by the resolution desk's own
actions, as themselves.

### `checkout` — `CheckoutAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `checkout.CheckoutSessionCreated` | No | A merchant opened a checkout session; the record names the session and the merchant by identifier, never the token and never what was bought. |
| `checkout.CheckoutSessionConfirmed` | No | A customer confirmed a checkout session; the record names the session and the payment intent by identifier. |
| `checkout.OrderCreated` | No | A capture completed a checkout session and produced its order; the record names the order, the session and the journal entry that paid for it. |
| `checkout.CheckoutSessionExpired` | No | The expiry sweeper ended a checkout session whose offer had run out; the record names the session and the state it expired from. |
| `checkout.CheckoutSessionAbandoned` | **Yes** | A merchant withdrew a checkout session before it was paid; the record names the session and the merchant, and the reason is required. |

**Four of the five checkout actions require no reason, and the fifth does** — the split is
`INV-AUD-03` working rather than an inconsistency (`P6-TSK-007`, `P6-TSK-008`). Creating,
confirming, producing an order and expiring are somebody, or *nobody*, doing the ordinary thing
the surface exists for: a merchant making an offer, a customer paying, a capture landing, a
deadline passing. Demanding a reason for those would make it a field callers fill with noise,
which is worse than not asking — and for the expiry there is not even anybody to ask, because
the sweeper acts as the platform.

`checkout.CheckoutSessionAbandoned` earns its reason: a merchant withdrawing an offer it already
made is a **judgement about somebody else's purchase**, the suspension and revocation shape. The
customer looking at the page finds their checkout gone, and the trail must be able to say why.

`checkout.CheckoutSessionExpired` records **the state the session expired from**, because `OPEN`
(nobody paid) and `PAYMENT_PENDING` (a payment that never landed) are different operational
facts and an auditor must be able to tell them apart.

`checkout.OrderCreated` names the **journal entry that paid for the order**, which is what
closes the traceable chain in the trail itself: order → entry → ADR-0050 §3's four lines → the
merchant's payable position.

### `settlement` — `SettlementAuditAction`

| Action | Reason required | What it records, and why |
|---|---|---|
| `settlement.SettlementDeliveryRefused` | No | The settlement door refused a delivery (`P8-TSK-002`, ADR-0066 §4): instrument data in screened text, or a delivery over the size or line bound. Written in the refusal's transaction; the change summary names the source, the reason, the content address and the finding's position — **never a value from the file** (`INV-PAY-02`, `INV-RAIL-03`). For a content refusal the `settlement.refused_delivery` metadata row stands beside it; an over-bound delivery leaves this record alone. Alertable from the first file through `finapp.settlement.delivery.refused`. |
| `settlement.SettlementFileUploaded` | No | A person introduced settlement evidence over the upload door (`P8-TSK-003`, ADR-0066 §1). Written in the reception's ONE transaction — row, chunks, receipt, birth event and this record commit together; the change summary names the source, the channel and the content address, never the content and never the business date's claim (`INV-AUD-02`). A duplicate delivery writes its `DUPLICATE` receipt and no second record: the receipt table is the arrivals' history, the audit record is the act's. |
| `settlement.SettlementFileReceivedByPull` | No | The platform received settlement evidence by pulling it (`P8-TSK-021`, ADR-0066 §1) over the source's own confined credential — the door's audit word for the `PULL` channel, written in the reception's ONE transaction exactly as an upload's; acting-only: a duplicate pull writes its `DUPLICATE` receipt and no second record. The change summary names the source, the channel and the content address, never the content (`INV-AUD-02`). |
| `settlement.SettlementFetchRequested` | No | An operator asked for a source's report to be pulled now (`P8-TSK-021`, `POST /v1/operator/settlement/sources/{code}/fetch`): written after the pull, in its own transaction, naming the source, the business key and what the fetch came to — never a byte of the report; a pull that threw is recorded `FAILED` naming the exception's class, then rethrown. An unknown or retired source writes nothing. |
| `settlement.SettlementFileAttested` | No | A second person attested an uploaded file (`P8-TSK-003`, `INV-SET-07`, `INV-AUD-04`): the `NULL → value` fact the accept leg (`P8-TSK-009`) will require before an upload can move money. Written in the attestation's transaction, after distinctness from the uploader is enforced at the domain AND by `V002`'s `CHECK` — so this record existing implies a genuine second person. The same attester's retry converges and writes no second record. |
| `settlement.SettlementFileContentRead` | **Yes** | Somebody read a settlement file's raw bytes — the ONE content path, `POST .../content-reads` under `RECONCILIATION_INVESTIGATE` (`INV-REC-10`): one record per read, committed with the read before a byte is served, the audited unit being the access (ADR-0036's rule). A verification failure — tamper, transplant, truncation — is the SAME record with outcome `FAILED` and nothing served; a guessed identifier records nothing, because there is no file to audit an access against. |

| `settlement.SettlementFileRejected` | No | The parse leg rejected a file WHOLE (`P8-TSK-008`, ADR-0066 §9, `INV-SET-07`): the platform's own verdict — acting-only, the process rank of `INV-AUD-04` — written in the rejecting transaction beside the `ingestion_error` rows, the file history and the published `settlement.SettlementFileRejected` event. The change summary names the source, the rejection code and the error count, never a value from the file. Our own failure writes NO such record: an adapter exception leaves the file `RECEIVED` with its back-off, recorded by the file's history. |
| `settlement.SettlementFileDeclined` | **Yes** | A person declined a settlement file (`P8-TSK-008`, moved from `-003` whose schema had no `REJECTED`): a reasoned judgement, never our validation — `RECEIVED \| PARSED → REJECTED(DECLINED)`, a parsed file's batch rejected in the SAME transaction so the live key frees at commit. A declined file is never readmitted (ADR-0066 §8); recovery is the counterparty's re-issue. |
| `settlement.SettlementFileReadmitted` | **Yes** | A reconciliation controller readmitted a file (`P8-TSK-022`, ADR-0066 §8, `POST .../readmission`): a NEW file naming its original, its bytes the original's - verified against the stored address and re-encrypted under the new id - born `RECEIVED` with the reason on its birth event. Only a file our own validation rejected, a declined file (which inherits nothing), or a conflicting batch's file whose conflict is gone is readmitted, once. The summary names both files and whether the readmission inherits its original's authentication - never a byte. Written in the readmission's one transaction; a door refusal of the re-screened bytes still records its metadata and this action. |
| `settlement.SettlementFileVerified` | **Yes** | An investigator re-parsed a stored file under its RECORDED format version (`P8-TSK-022`, ADR-0066 §9, `POST .../verification`) and compared it line by line with the stored normalisation - it reads the content, so it is reasoned per access (`INV-REC-10`). The summary carries the verdict (`MATCHES`, `DIFFERS` with the first differing line, `NOT_PARSED`, `FORMAT_VERSION_UNAVAILABLE`, `CORRUPT`) and the count compared - never a value. Nothing is replaced: a divergence is a finding, not a repair. |

| `settlement.SettlementBatchAccepted` | No | The accept leg recognised a batch (`P8-TSK-009`, ADR-0065 §2): the platform's own act, acting-only — a losing racer records nothing — written in the acceptance transaction beside the gapless sequence, the run, the items, the remittance expectation and the recognition posting (or its honest omission at zero fees). The change summary carries identifiers and counts only: source, sequence, items, whether a remittance opened, whether the posting was omitted — never an amount. The person who authenticated an upload is already on the record (the attestation's own row); attributing the recognition to them would record them as acting at a moment they chose nothing. |
| `settlement.SettlementBatchRepudiated` | No | An approved `REPUDIATE_BATCH` moved the batch `ACCEPTED → REPUDIATED` (`P8-TSK-023`, ADR-0065 §10), written in the approval's one transaction by the approver, after the reversal posted: the summary names the source, the file, the resolution and the reversal entry (or `none` for a batch that posted nothing) - identifiers only. The reasoned act is the resolution's: `reconciliation.ResolutionProposed` carries the narrative's reason, never its body. The file stays `ACCEPTED`, retained byte-identical. |

The pull's actions arrived with the pull (`P8-TSK-021`); the readmission's and the verification's with `P8-TSK-022`.

### `reconciliation` — `ReconciliationAuditAction`

| Action | Reason required | What it records, and why |
|---|---|---|
| `reconciliation.OpeningPositionRecorded` | **Yes** | A reconciliation controller adopted the opening position (`P8-TSK-007`, ADR-0067 §8): Phases 5–7's completed clearing operations opened as tracked expectations through the live recorder's own path, converging on the register's uniques. One record per recorded run, keyed per principal and committed after the walk; the change summary carries the per-producer **counts only, never an amount** (`INV-AUD-02`), and the reason is the controller's own — adopting history decides what every proof and break is judged against, which is why the act demands one. |
| `reconciliation.BreakResolvedByEvidence` | No | The platform closed a break by evidence (`P8-TSK-012`, ADR-0071 §2): a counterparty's own correction or claw-back explained the discrepancy to a zero residual, and the `EVIDENCED` resolution — born `APPROVED`, no person deciding — closed it in the same transaction. Acting-only; the change summary names the break, resolution, decision and park — identifiers and enumerated names only, never an amount (`INV-AUD-02`). |
| `reconciliation.RunCompleted` | No | A reconciliation run completed (`P8-TSK-011`, ADR-0068 §6): every item disposed, the counts per outcome in the change summary — identifiers and counts only, never an amount (`INV-AUD-02`) — acting-only: a scheduled decision over locked, stored rows has no person, and the losing edge of the conditional completion records nothing. |
| `reconciliation.BreakRaised` | No | The platform raised a classified break from a stored fact it detected (`P8-TSK-010`, ADR-0069 §3) — acting-only: a raise that converged on the standing open break records nothing. The change summary carries the type, cause, severity and source id — identifiers and enumerated names only, never an amount (`INV-AUD-02`); the value at issue is the row's and the audited reports'. |
| `reconciliation.BreakAssigned` | No | An investigator assigned a break (`P8-TSK-014`, ADR-0069 §7): the first assignment moves `OPEN → INVESTIGATING` (and publishes `reconciliation.BreakInvestigationStarted`), later ones hand the case over. The change summary names the assignee, the previous one and the status edge — identifiers and enumerated names only. A repeat of the standing assignee converges and records nothing. |
| `reconciliation.BreakNoteAdded` | No | An investigator appended a note to a break's case file (`P8-TSK-014`). **The body is never in the record**: it is CONFIDENTIAL, screened at the domain and the database for card-number and bank-account shapes, and lives only in `break_note` — the summary names the note's id and length. |
| `reconciliation.BreakEvidenceLinked` | No | An investigator linked stored evidence to a break by identifier (`P8-TSK-014`): a settlement file, batch or line, a journal entry, an operation, a provider evidence row, a run or a decision, verified to exist in the same transaction. The summary names the link, the target kind and the target's identifier. |
| `reconciliation.BreakReclassified` | **Yes** | An investigator moved a break's type (`P8-TSK-014`, ADR-0069 §7) — in `OPEN` or `INVESTIGATING` only, onto a type that stands on the break's subject and parks exactly when it holds parked value (`INV-REC-09`). The reason is the investigator's own (screened like a note); the summary names the type and the severity before and after — the cause, subject and value at issue never change. |
| `reconciliation.ResolutionProposed` | **Yes** | An investigator proposed a template-bound break resolution (`P8-TSK-015`, ADR-0071 §6): the break moved to `RESOLUTION_PROPOSED` and, for a posting kind, the ledger proposal was recorded beside it through the owned door (`ledger.AdjustmentProposed` then names the same person). The reason is the kind and the reason code; the summary names the break, the four-eyes flag and the ledger proposal — **never the narrative** (CONFIDENTIAL, stored on the resolution alone) and never an amount (`INV-AUD-02`). |
| `reconciliation.ResolutionApproved` | No | A second person approved a resolution (`P8-TSK-015`, ADR-0071 §§4, 6): for a posting kind the `ADJUSTMENT` entry posted (`ledger.AdjustmentPosted` names the same approver), the subject's value was disposed of and the break — with any sibling break answering for the same remainder, or the offset's partner — moved to `RESOLVED`, all in one transaction. Also the ONE record of a zero-value `ACKNOWLEDGE`, one person's act, which then carries its reason (the kind and code). The summary names the break, kind, code, proposer and entry — identifiers and enumerated names only. |
| `reconciliation.ResolutionRejected` | **Yes** | Another `RECONCILIATION_RESOLVE` holder rejected a pending resolution (`P8-TSK-015`, ADR-0071 §10) — a reasoned act, screened like a note; the ledger proposal was rejected beside it and the break returned to `INVESTIGATING`. |
| `reconciliation.ResolutionWithdrawn` | No | A pending resolution was withdrawn (`P8-TSK-015`, ADR-0071 §§1, 9): by its proposer, or by the platform when evidence closed the break first (the summary then says `withdrawnBy=EVIDENCE` and names the evidencing resolution). Nothing is deleted — the row moved to `WITHDRAWN` — and the ledger proposal was rejected. |
| `reconciliation.ReportRead` | No | Somebody was served a reconciliation report that carries amounts — the positions report first (`P8-TSK-007`, ADR-0072; the `payments.ChargebackRatioRead` precedent). One record per serving, committed in the reading's own transaction; the summary names the report and its shape, never its figures. Since `P8-TSK-024` the five reports (`positions`, `suspense`, `unmatched`, `summary`, `provider-costs`) each write one record per serving, its detail `report=<name>, period=<p|none>, rows=N` - never an amount or a counterparty; a refused period writes none. |
| `reconciliation.RuleSetProposed` | **Yes** | A reconciliation controller proposed a matching rule set version (`P8-TSK-022`, ADR-0068 §8): its content frozen from the proposal by trigger, awaiting a DIFFERENT controller. The summary names the source and the version; the reason is the proposer's. |
| `reconciliation.RuleSetActivated` | **Yes** | A second controller activated a proposed version (`P8-TSK-022`, `INV-AUD-04`), its predecessor retired in the same transaction - a source always has exactly one active version. The summary names both versions; a new version governs only new decisions. |
| `reconciliation.RuleSetRejected` | **Yes** | A controller rejected a proposed version (`P8-TSK-022`) - the proposer withdrawing their own included, since declining a change changes no policy. |
| `reconciliation.ReprocessingRequested` | **Yes** | A controller opened a `REPROCESS` run (`P8-TSK-022`, ADR-0068 §9.2): the source's residual items re-decided under the active version as NEW decisions. The summary names the run, the version and the worklist's size - a count, never an amount. |
| `reconciliation.RunRequeued` | **Yes** | A controller requeued a blocked run (`P8-TSK-022`): `BLOCKED -> IN_PROGRESS`, its failures reset; its `RUN_BLOCKED` break stands until the run completes and closes it `EVIDENCED`. |
| `reconciliation.RunReplayed` | No | An investigator replayed a run's stored decisions (`P8-TSK-022`, ADR-0068 §9.1): the summary carries the verdict and the counts - replayed, not replayed, diverged, pending rematch; a divergence raised its CRITICAL break beside it. A replay explains, it never repairs. |

Matching's, the breaks' and the resolutions' actions arrive with their tasks (`P8-TSK-010`,
`-015`); the administration's (rule sets, reprocessing, requeue, replay) with `P8-TSK-022`; the
repudiation's with `P8-TSK-023`.

### What is emitted, and what is declared not to be

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
