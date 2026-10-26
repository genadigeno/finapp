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
| `kyc.CounterpartyScreeningDecided` | **Yes** | kyc decided a counterparty screening - automatically from the provider's verdict and the payee check, or a reviewer's release or block - naming its basis and policy version. |

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
`kyc.CounterpartyScreeningDecided` joined at `P9-TSK-016` (ADR-0081): **every** outcome of a
counterparty screening - `CLEAR`, `IN_REVIEW`, `UNAVAILABLE` by the platform, `RELEASED` or
`BLOCKED` by a person - is a recorded decision (`INV-KYC-01` extended), written in the decision's
own transaction. **Reason required**: an outcome nobody can explain is one nobody can defend. The
platform's reason names the provider's verdict, the payee check and the outcome (*"the provider
answered HIT and the payee check was MATCH: decided IN_REVIEW (HIT)"*); a reviewer's is the reason
code and the narrative. The change summary carries the screening id, status, basis, policy version
and attempts - **never the counterparty's name**, which rests only in kyc's ciphertext (the needle).

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
| `accounts.WalletCurrencyAdded` | No | A wallet currency was added to a customer account product; the record names the account, the currency and the ledger account, never a balance. |

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
`accounts.WalletCurrencyAdded` arrived with `P9-TSK-004` (ADR-0076 §6): the add-currency door's
act, no reason for the opening's own argument. Emitted by `AccountOpening.addCurrency` only when
**its** insert created the wallet account — a converged repeat, or a racing loser handed the
winner's row, is not a second act. The code matches the event every wallet opening publishes
(`WalletAccounts.openIfAbsent` writes it on every open path, the first currency at opening
included), but the record is the door's alone: the conversion and return paths that will reuse
the open audit their own acts.

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
| `payments.WithdrawalDispatched` | No | A customer dispatched a wallet withdrawal (`P7-TSK-008`): judged under the wallet's lock, held, routed and committed with our minted reference before the scheme is asked; the record names the withdrawal, the wallet account, the instrument and the rail, never an amount. *(`P11-TSK-015`: also the platform's act - the SYSTEM actor - when the withdrawal is dispatched on an instructor's recorded instruction (lending's external payout, `withdrawOnInstruction`), adopting the instructor's hold; the same action, the same record, under the acceptance's correlation. Lending records no audit action of its own for a payout.)* |
| `payments.WithdrawalHeld` | No | A customer's wallet withdrawal was judged `HOLD` by the risk seam in its dispatch transaction (`P13-TSK-009`, payments `V033`): born `HELD` under the wallet's lock, its ledger hold KEPT, routed and referenced, nothing sent. The person's act, as `payments.WithdrawalDispatched` is for a permitted one; the record names the withdrawal, the wallet account, the instrument, the rail and the status - never an amount, a reason or a judgement (`INV-RSK-08`). |
| `payments.WithdrawalRefused` | No | A customer's wallet withdrawal was refused by the risk seam in its dispatch transaction (`P13-TSK-009`): born `FAILED(RISK_REFUSED)`, the hold placed under the wallet's lock released in the same transaction, nothing sent. The person's act, recorded `FAILED`; the record names the withdrawal, the wallet account, the instrument, the rail and the status - never an amount, a reason or a judgement. |
| `payments.WithdrawalReleased` | **Yes** | A `HELD` withdrawal released through payments' keyed door (`Withdrawals.releaseHeld`, `P13-TSK-009`; reached by `-012`'s approved override and `-017`'s risk action through risk's `HeldWithdrawalActions`): `HELD → DISPATCHED` under the withdrawal row's lock within its hold validity on the database clock, the first real send permit stamped, the send after the commit. The door's caller is the actor (the approving operator, or the platform's action schedule); the reason is a reason code (`[A-Z][A-Z0-9_]{0,63}`), never a note; the summary names the withdrawal, its status and the door's key (`risk.action:<id>`). The send's outcome is `payments.WithdrawalOutcomeApplied`, the platform's. |
| `payments.WithdrawalDenied` | **Yes** | A `HELD` withdrawal denied through payments' keyed door (`Withdrawals.denyHeld`, `P13-TSK-009`): `HELD → FAILED(RISK_REFUSED)` and its hold released in one transaction under the withdrawal row's lock - the row, then the wallet. The door's caller is the actor; the reason a reason code; the summary names the withdrawal, the status, the failure class and the door's key. |
| `payments.WithdrawalHoldExpired` | No | A `HELD` withdrawal's hold validity lapsed on the database clock (`P13-TSK-009`; `PHASE_13_PLAN.md` §5, §14): failed `RISK_REFUSED` with the detail `HOLD_EXPIRED` and its hold released by the expiry leg of `WithdrawalResolutionSchedule` - the platform's act (an enumerated `enterSystem()` site), once, the row claimed `FOR UPDATE SKIP LOCKED`; the risk case, if one is open, is untouched. The summary names the withdrawal and the detail - never an amount. |
| `payments.PaymentRiskRefused` | No | A person's checkout payment confirmation was refused by the risk seam in its first transaction (`P13-TSK-010`, payments `V034`): the intent `REQUIRES_CONFIRMATION → FAILED` with its own `RISK_REFUSED` under the intent row's lock - no attempt, no routing decision, nothing sent - and terminal: a re-confirmation is the machine's `409`. The person's act, recorded `FAILED`; the summary names the intent and the instrument kind - never an amount, a reason or a judgement. |
| `payments.PaymentCaptureDeferred` | No | A person's card payment was confirmed under the risk seam's `HOLD` (`P13-TSK-010`, slice 2): the authorisation dispatched as any card payment's and its capture deferred behind a capture hold born open beside the attempt, its window on the database clock. The person's act, beside `payments.PaymentConfirmed`; the summary names the intent and the attempt. |
| `payments.HeldPaymentCaptured` | **Yes** | A held card payment released for capture through payments' keyed door (`HeldPayments.captureHeld`, `P13-TSK-010`; reached by `-012`'s approved override and `-017`'s risk action `CAPTURE_HELD_PAYMENT`): the capture hold resolved `CAPTURE` under its row's lock within `held_until` on the database clock, then the capture dispatched by the existing capture command. The door's caller is the actor; the reason a reason code; the summary names the intent, the attempt and the door's key. |
| `payments.HeldPaymentVoided` | **Yes** | A held card payment voided through payments' keyed door (`HeldPayments.voidHeld`, `P13-TSK-010`; `VOID_HELD_PAYMENT`): the capture hold resolved `VOID` under its row's lock, then the authorisation released by the existing void command (`payments.PaymentVoidDispatched` beside it). The door's caller is the actor; the reason a reason code; the summary names the intent, the attempt and the door's key. |
| `payments.HeldPaymentExpired` | No | A held card payment's hold validity lapsed on the database clock (`P13-TSK-010`; `PHASE_13_PLAN.md` §5, §14): the capture hold resolved `EXPIRED` by the deadline leg of `PaymentSweeperSchedule` - the platform's act (an enumerated `enterSystem()` site), once, the row claimed `FOR UPDATE SKIP LOCKED` - and the authorisation then released by the void command (reason `HOLD_EXPIRED`); the risk case, if one is open, is untouched. The summary names the intent and the attempt - never an amount. |
| `payments.WithdrawalOutcomeApplied` | No | The scheme's word landed on a withdrawal — applied by the dispatching flight, a takeover or the inquiry sweep, the platform's act on the locked row, acting once; the record names the withdrawal, the status, the failure class and the resolver, never an amount or a reference. |
| `payments.OutboundCreditOutcomeApplied` | No | A corridor provider's word landed on a cross-border outbound credit (`P9-TSK-020`) - the synchronous send's answer, a callback's hinted inquiry or the resolution sweep, the platform's act on the locked row, acting once: `RECEIVED`, `UNKNOWN`, the completion (the claim, the hold released, the entry, the trade, the payment `SENT` and the expectation in one transaction), a delivery, or a failure (`DECLINED`, `PROVIDER_UNAVAILABLE`, `NEVER_RECEIVED`). The summary names the credit, the status, the failure class and the resolver - never an amount or a reference. No reason: the platform's own resolution. |
| `payments.OutboundCreditReturnApplied` | No | A cross-border outbound credit's return recorded once (`P9-TSK-023`): by the applier from evidence - the inquiry channel or the corridor report's `PAYOUT_RETURNED` line, the platform's act on the locked credit (the entry `crossborder-return:`, the fact, the payment `RETURNED`, the expectation in one transaction) - or by a four-eyes `TRANSFER_TO_ACCOUNT`'s approval, recorded as the approver's act beside the resolution's own records (the fact, the fee refund, the payment `RETURNED`). The summary names the credit, `applied_by` and the channel or resolution - never an amount or a reference. No reason: the applier's is the platform's own; the resolution carries its own reason code and narrative. |
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
| `merchant.BnplEnablementProposed` | **Yes** | An operator proposed enabling BNPL at a merchant; nothing is offered until a second operator approves; the reason is required. |
| `merchant.BnplEnablementApproved` | **Yes** | A second operator, distinct from the proposer, approved a merchant's BNPL enablement; the reason is required. |
| `merchant.BnplEnablementRejected` | **Yes** | An operator rejected a merchant's BNPL enablement proposal - terminal; the reason is required. |
| `merchant.BnplEnablementWithdrawn` | **Yes** | An operator withdrew a merchant's BNPL enablement - terminal; financed purchases are untouched; the reason is required. |
| `merchant.BnplEnablementApprovalRefused` | No | The proposer of a merchant's BNPL enablement tried to approve it and was refused (INV-AUD-04); recorded FAILED in a transaction of its own. |
| `merchant.BnplFeeScheduleAssigned` | **Yes** | An operator assigned a merchant's BNPL product to a fee schedule whose versions return their fee on refund; the record names the merchant, the product and both schedules; the reason is required. |

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

**The six merchant BNPL actions** (`P12-TSK-006`; OQ-8, `INV-AUD-04`). Proposing, approving,
rejecting and withdrawing a merchant's BNPL enablement are each an operator's judgement about the
merchant's standing, reasoned (`INV-AUD-03`); the target is `merchant.BnplEnablement` by id, the
summary names it and its merchant. `merchant.BnplEnablementApprovalRefused` is the refused
self-approval, recorded `FAILED` **in a transaction of its own after the act rolled back** - the
Phase 11 gate's R4, applied from this module's second four-eyes subject (the payout destination's
older `DENIED`-in-the-same-transaction shape is kept for it, unchanged); it keeps no reason, its
summary names the enablement, the merchant and `act=bnpl-enablement:approve`. A withdrawal is one
operator's act, the suspension's precedent: stopping BNPL reduces risk. `merchant.BnplFeeScheduleAssigned`
is emitted by the moving call only, as `MerchantFeeScheduleAssigned` is. The fee pin a purchase is
born with is not audited here: it is part of the purchase's own transaction and record (`P12-TSK-013`).

### `checkout` — `CheckoutAuditAction`

| Code | Reason required | What it is |
|---|---|---|
| `checkout.CheckoutSessionCreated` | No | A merchant opened a checkout session; the record names the session and the merchant by identifier, never the token and never what was bought. |
| `checkout.CheckoutSessionConfirmed` | No | A customer confirmed a checkout session; the record names the session and the payment intent by identifier. |
| `checkout.OrderCreated` | No | A capture or a BNPL financing completed a checkout session and produced its order; the record names the order, the session and the journal entry that paid for it - and, for a financing, the purchase. |
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
| `settlement.SettlementFetchStarted` | No | An operator's fetch took its permit (the Phase 8 -> 9 transition, SEC-08): written in the permit's own transaction - the fetch's first effect, before the external read and the door - naming the operator, the source and the business key, never a byte of the report. The fetch is three steps that no one transaction holds, so a process dying past its permit never reaches `settlement.SettlementFetchRequested`; this record is what such a fetch is traced back to. A source unknown, retired or not pulled takes no permit and writes none. |
| `settlement.SettlementFetchRequested` | No | An operator asked for a source's report to be pulled now (`P8-TSK-021`, `POST /v1/operator/settlement/sources/{code}/fetch`): written after the pull, in its own transaction, naming the source, the business key and what the fetch came to — never a byte of the report; a pull that threw is recorded `FAILED` naming the exception's class, then rethrown. An unknown or retired source writes nothing. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition, SEC-08: this record closes the fetch; its start is `settlement.SettlementFetchStarted`, committed with the permit, so a fetch that dies before this record is still on the trail.)* |
| `settlement.SettlementFileAttested` | No | A second person attested an uploaded file (`P8-TSK-003`, `INV-SET-07`, `INV-AUD-04`): the `NULL → value` fact the accept leg (`P8-TSK-009`) will require before an upload can move money. Written in the attestation's transaction, after distinctness from the uploader is enforced at the domain AND by `V002`'s `CHECK` — so this record existing implies a genuine second person. The same attester's retry converges and writes no second record. |
| `settlement.SettlementFileContentRead` | **Yes** | Somebody read a settlement file's raw bytes — the ONE content path, `POST .../content-reads` under `RECONCILIATION_INVESTIGATE` (`INV-REC-10`): one record per read, committed with the read before a byte is served, the audited unit being the access (ADR-0036's rule). A verification failure — tamper, transplant, truncation — is the SAME record with outcome `FAILED` and nothing served; a guessed identifier records nothing, because there is no file to audit an access against. |

| `settlement.SettlementFileRejected` | No | The parse leg rejected a file WHOLE (`P8-TSK-008`, ADR-0066 §9, `INV-SET-07`): the platform's own verdict — acting-only, the process rank of `INV-AUD-04` — written in the rejecting transaction beside the `ingestion_error` rows, the file history and the published `settlement.SettlementFileRejected` event. The change summary names the source, the rejection code and the error count, never a value from the file. Our own failure writes NO such record: an adapter exception leaves the file `RECEIVED` with its back-off, recorded by the file's history. |
| `settlement.SettlementFileDeclined` | **Yes** | A person declined a settlement file (`P8-TSK-008`, moved from `-003` whose schema had no `REJECTED`): a reasoned judgement, never our validation — `RECEIVED \| PARSED → REJECTED(DECLINED)`, a parsed file's batch rejected in the SAME transaction so the live key frees at commit. A declined file is readmissible, once (ADR-0066 §8, `P8-TSK-022`): its readmission inherits no authentication and is accepted only once attested by a holder of `SETTLEMENT_INGEST` distinct from every submitter along its chain (settlement `V009`) - a byte-identical re-issue would meet the declined file's content address as its duplicate. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition, SEC-09: this read "a declined file is never readmitted; recovery is the counterparty's re-issue", which `P8-TSK-022`'s design overturned - an auditor reading it would have missed every declined file's bytes re-entering through `settlement.SettlementFileReadmitted`.)* |
| `settlement.SettlementFileReadmitted` | **Yes** | A reconciliation controller readmitted a file (`P8-TSK-022`, ADR-0066 §8, `POST .../readmission`): a NEW file naming its original, its bytes the original's - verified against the stored address and re-encrypted under the new id - born `RECEIVED` with the reason on its birth event. Only a file our own validation rejected, a declined file (which inherits nothing), or a conflicting batch's file whose conflict is gone is readmitted, once. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition, MI-2: or an accepted file whose batch was repudiated, which inherits its original's authentication; the summary's `originalVerdict` then reads `REPUDIATED_BATCH`.)* *(Corrected 2026-10-03 by the Phase 8 -> 9 transition's re-gate, NEW-SEC-1: it inherits nothing - a repudiated batch's file passes nothing on (settlement `V014`), so its readmission is accepted only once attested by a person distinct from every submitter along its chain.)* The summary names both files and whether the readmission inherits its original's authentication - never a byte. Written in the readmission's one transaction; a door refusal of the re-screened bytes still records its metadata and this action. |
| `settlement.SettlementFileVerified` | **Yes** | An investigator re-parsed a stored file under its RECORDED format version (`P8-TSK-022`, ADR-0066 §9, `POST .../verification`) and compared it line by line with the stored normalisation - it reads the content, so it is reasoned per access (`INV-REC-10`). The summary carries the verdict (`MATCHES`, `DIFFERS` with the first differing line, `NOT_PARSED`, `FORMAT_VERSION_UNAVAILABLE`, `CORRUPT`) and the count compared - never a value. Nothing is replaced: a divergence is a finding, not a repair. |

| `settlement.SettlementBatchAccepted` | No | The accept leg recognised a batch (`P8-TSK-009`, ADR-0065 §2): the platform's own act, acting-only — a losing racer records nothing — written in the acceptance transaction beside the gapless sequence, the run, the items, the remittance expectation and the recognition posting (or its honest omission at zero fees). The change summary carries identifiers and counts only: source, sequence, items, whether a remittance opened, whether the posting was omitted — never an amount. The person who authenticated an upload is already on the record (the attestation's own row); attributing the recognition to them would record them as acting at a moment they chose nothing. |
| `settlement.SettlementBatchRepudiated` | No | An approved `REPUDIATE_BATCH` moved the batch `ACCEPTED → REPUDIATED` (`P8-TSK-023`, ADR-0065 §10), written in the approval's one transaction by the approver, after the reversal posted: the summary names the source, the file, the resolution and the reversal entry (or `none` for a batch that posted nothing) - identifiers only. The reasoned act is the resolution's: `reconciliation.ResolutionProposed` carries the narrative's reason, never its body. The file stays `ACCEPTED`, retained byte-identical. |

The pull's actions arrived with the pull (`P8-TSK-021`); the readmission's and the verification's with `P8-TSK-022`.

### `reconciliation` — `ReconciliationAuditAction`

| Action | Reason required | What it records, and why |
|---|---|---|
| `reconciliation.OpeningPositionStarted` | **Yes** | A reconciliation controller began the opening position's walk (the Phase 8 -> 9 transition, SEC-08): written in the act's own first transaction, after the reason's screen and before the walk's first page, with the controller's reason. The walk is many transactions - one page per producer each - and its counts exist only at its end (`reconciliation.OpeningPositionRecorded`, the same correlation), so every expectation, parking owner or remittance it adopts follows a committed record of who began it and why. A start with no recorded run beside it is a walk that crashed or was refused at its claim; one per invocation, a same-key replay re-walking too. |
| `reconciliation.OpeningPositionRecorded` | **Yes** | A reconciliation controller adopted the opening position (`P8-TSK-007`, ADR-0067 §8): Phases 5–7's completed clearing operations opened as tracked expectations through the live recorder's own path, converging on the register's uniques. One record per recorded run, keyed per principal and committed after the walk; the change summary carries the per-producer **counts only, never an amount** (`INV-AUD-02`), and the reason is the controller's own — adopting history decides what every proof and break is judged against, which is why the act demands one. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition, SEC-08: this record closes the walk; its start is `reconciliation.OpeningPositionStarted`, committed before the first page, so a crash mid-walk leaves its adopted prefix attributed.)* |
| `reconciliation.BreakResolvedByEvidence` | No | The platform closed a break by evidence (`P8-TSK-012`, ADR-0071 §2): a counterparty's own correction or claw-back explained the discrepancy to a zero residual, and the `EVIDENCED` resolution — born `APPROVED`, no person deciding — closed it in the same transaction. Acting-only; the change summary names the break, resolution, decision and park — identifiers and enumerated names only, never an amount (`INV-AUD-02`). |
| `reconciliation.RunCompleted` | No | A reconciliation run completed (`P8-TSK-011`, ADR-0068 §6): every item disposed, the counts per outcome in the change summary — identifiers and counts only, never an amount (`INV-AUD-02`) — acting-only: a scheduled decision over locked, stored rows has no person, and the losing edge of the conditional completion records nothing. |
| `reconciliation.BreakRaised` | No | The platform raised a classified break from a stored fact it detected (`P8-TSK-010`, ADR-0069 §3) — acting-only: a raise that converged on the standing open break records nothing. The change summary carries the type, cause, severity and source id — identifiers and enumerated names only, never an amount (`INV-AUD-02`); the value at issue is the row's and the audited reports'. |
| `reconciliation.BreakAssigned` | No | An investigator assigned a break (`P8-TSK-014`, ADR-0069 §7): the first assignment moves `OPEN → INVESTIGATING` (and publishes `reconciliation.BreakInvestigationStarted`), later ones hand the case over. The change summary names the assignee, the previous one and the status edge — identifiers and enumerated names only. A repeat of the standing assignee converges and records nothing. |
| `reconciliation.BreakNoteAdded` | No | An investigator appended a note to a break's case file (`P8-TSK-014`). **The body is never in the record**: it is CONFIDENTIAL, screened at the domain and the database for card-number and bank-account shapes, and lives only in `break_note` — the summary names the note's id and length. |
| `reconciliation.BreakEvidenceLinked` | No | An investigator linked stored evidence to a break by identifier (`P8-TSK-014`): a settlement file, batch or line, a journal entry, an operation, a provider evidence row, a run or a decision, verified to exist in the same transaction. The summary names the link, the target kind and the target's identifier. |
| `reconciliation.BreakReclassified` | **Yes** | An investigator moved a break's type (`P8-TSK-014`, ADR-0069 §7) — in `OPEN` or `INVESTIGATING` only, onto a type that stands on the break's subject and parks exactly when it holds parked value (`INV-REC-09`). The reason is the investigator's own (screened like a note); the summary names the type and the severity before and after — the cause, subject and value at issue never change. |
| `reconciliation.ResolutionProposed` | **Yes** | An investigator proposed a template-bound break resolution (`P8-TSK-015`, ADR-0071 §6): the break moved to `RESOLUTION_PROPOSED` and, for a posting kind, the ledger proposal was recorded beside it through the owned door (`ledger.AdjustmentProposed` then names the same person). The reason is the kind and the reason code; the summary names the break, the four-eyes flag and the ledger proposal — **never the narrative** (CONFIDENTIAL, stored on the resolution alone) and never an amount (`INV-AUD-02`). |
| `reconciliation.ResolutionApproved` | No | A second person approved a resolution (`P8-TSK-015`, ADR-0071 §§4, 6): for a posting kind the `ADJUSTMENT` entry posted (`ledger.AdjustmentPosted` names the same approver), the subject's value was disposed of and the break — with any sibling break answering for the same remainder, or the offset's partner — moved to `RESOLVED`, all in one transaction. Also the ONE record of a timing difference's zero-value `ACKNOWLEDGE` (raised by a timing detector; every other acknowledgement is four-eyes — `P8-TST-002`), one person's act, which then carries its reason (the kind and code). The summary names the break, kind, code, proposer and entry — identifiers and enumerated names only. |
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

### `fx` — `FxAuditAction`

| Action | Reason required | What it records, and why |
|---|---|---|
| `fx.ConversionExecuted` | No | The owner converted between two of their wallets (`P9-TSK-009`) - a customer act, written in the conversion's one transaction (T-a) beside the trade, its entry `fx-trade:<id>` and the cover row. The summary names the quote and the pair; never an amount (`INV-AUD-02`) - the amounts are the trade's and the entry's. |
| `fx.QuoteCancelled` | No | The owner cancelled a live quote (`P9-TSK-008`) - a customer act, under the quote row's lock, conditional on `expires_at > statement_timestamp()`. The summary names the quote; a customer's cancellation needs no reason. Issuance and expiry are not audit records: the quote's own history (`fx.quote_event`) and events (`fx.FxQuoteIssued`, `fx.FxQuoteExpired`) record them, and the expiry has no person. |
| `fx.CoverExecuted` | No | The platform's cover executed with its provider (`P9-TSK-012`, ADR-0077) - a platform act, written by the acting applier only, in the outcome transaction (T-d) beside the execution fact, the entry `fx-cover:<id>` and the two leg expectations; the losers of a race record nothing. The summary names the quote, the attempt, the provider and whether the fixed leg went off plan; never an amount (`INV-AUD-02`) - the amounts are the execution fact's and the entry's. |
| `fx.CoverRequoted` | No | A definitively rejected cover was requoted at a fresh, plausible firm quote (`P9-TSK-012`, `INV-FX-08`) - a platform act, written by the acting requoter only, in the transaction that stored attempt n+1 and its reference before any send. The summary names the quote, the new attempt and the provider. |
| `fx.CoverUnwound` | No | An executed cover its quote no longer wants was unwound (`P9-TSK-021`, ADR-0077 sections 7 and 8) - its mirror created by whichever writer found the position unwanted under the lock order (the abandonment writer or the cover's applier), acting once; the summary names the quote, the cover and the provider - never an amount. No reason: the platform's own rule over state. |
| `fx.FxPositionReportRead` | No | An investigator read the FX position report (`P9-TSK-027`, `FX_INVESTIGATE`): `FX_POSITION` by currency and the open cover legs, in one snapshot, bounded at 100 rows. Every serving is recorded, in the read's transaction. The summary names the read, never an amount. |
| `fx.FxRevenueReportRead` | No | An investigator read a month's FX revenue report (`P9-TSK-027`, `FX_INVESTIGATE`): margins, the spread/markup split, residuals, realised results and off-plan covers. Every serving is recorded, in the read's transaction; the summary names the month. |
| `fx.FxTradeReversalProposed` | **Yes** | An operator holding `FX_TRADE_REVERSE` proposed reversing a booked wallet conversion (`P9-TSK-025`, the lifecycle document §3.3), with a reason - one live proposal per trade. The summary names the trade; the reason is the operator's. |
| `fx.FxTradeReversalApproved` | **Yes** | A different operator approved a trade reversal (`P9-TSK-025`, `INV-AUD-04`): the exact mirror entry through the ledger's reversal (its `V009` bound), the trade `REVERSED` and the cover's consequence (an executed cover unwound, a rejected one voided) in one transaction. The summary names the trade and the cover's effect - never an amount. |
| `fx.FxTradeReversalRejected` | **Yes** | A different operator rejected a trade reversal (`P9-TSK-025`), with a reason; nothing moved. |
| `fx.CoverVoided` | No | A rejected cover its quote no longer wants was voided (`P9-TSK-012`, ADR-0077 section 7) - a platform act; nothing was ever executed under it. The summary names the quote and the statuses that made it unwanted. |
| `fx.TradeProvenanceRead` | No | An investigator read an FX trade's provenance (`P9-TSK-013`, `FX_INVESTIGATE`, held by `RECONCILIATION_OPERATOR`) - the whole rate chain, the entry, the cover and its execution of somebody else's conversion, so every serving is recorded in the read's own transaction. The summary names the quote; never an amount or a rate. |
| `fx.PricingPolicyProposed` | **Yes** | An FX controller proposed a whole pricing policy version (`P9-TSK-007`, ADR-0075 §7): every (pair, purpose) row frozen from the proposal by trigger, awaiting a DIFFERENT controller. The summary names the version, the row count and the open-quote cap - never a margin; the reason is the proposer's. |
| `fx.PricingPolicyActivated` | **Yes** | A second controller activated a proposed version (`P9-TSK-007`, `INV-AUD-04`), its predecessor retired first in the same transaction - at most one version is ever `ACTIVE`, and a retirement commits only beside its successor. The summary names both versions; a new version prices only new quotes (`INV-HIST-04`). |
| `fx.PricingPolicyRejected` | **Yes** | A controller rejected a proposed version (`P9-TSK-007`) - the proposer withdrawing their own included, since declining a change changes no price. |
| `fx.AvailabilityDisabled` | **Yes** | One FX controller stopped a pair or a provider at once (`P9-TSK-007`, the lifecycle document §3.10): one append-only fact, and `fx.FxAvailabilityChanged` published. Stopping money must never wait for a second person. A disable of what is already stopped converges: no fact, no event and no record - nothing changed. |
| `fx.AvailabilityEnableProposed` | **Yes** | A controller proposed restarting a stopped pair or provider (`P9-TSK-007`): nothing is enabled by it - one live proposal per subject. |
| `fx.AvailabilityEnabled` | **Yes** | A second controller approved the enabling (`INV-AUD-04`): the enabling fact names the APPROVED request, so no fact enables without one (held by `fx V004`'s trigger for every writer), and `fx.FxAvailabilityChanged` published. |
| `fx.AvailabilityEnableRejected` | **Yes** | A controller rejected an enable proposal - the proposer withdrawing their own included; the subject stays stopped. |

### `crossborder` — `CrossborderAuditAction`

| Action | Reason required | What it records, and why |
|---|---|---|
| `crossborder.CorridorPolicyProposed` | **Yes** | A holder of CROSSBORDER_ADMINISTER proposed a whole corridor policy version (`P9-TSK-015`, ADR-0080 §4): every corridor frozen from the proposal by trigger, its rails declared by the build and covering the destination, awaiting a DIFFERENT person. The summary names the version and the corridor count - never a fee; the reason is the proposer's. |
| `crossborder.CorridorPolicyActivated` | **Yes** | A different person activated a proposed corridor policy version (`INV-AUD-04`), the build judged again, retiring its predecessor in the same transaction; the event `crossborder.CorridorPolicyActivated` carries the version. |
| `crossborder.CorridorPolicyRejected` | **Yes** | A proposed corridor policy version was rejected - or withdrawn by its proposer; no corridor changed. |
| `crossborder.CorridorDisabled` | **Yes** | One person disabled a corridor with a reason, at once (stopping money never waits for a second person); the event `crossborder.CorridorAvailabilityChanged` follows. |
| `crossborder.CorridorEnableProposed` | **Yes** | Enabling a corridor was proposed; nothing is enabled until a different person approves. |
| `crossborder.CorridorEnabled` | **Yes** | A different person approved a corridor enable proposal (`INV-AUD-04`) and the enabling fact was appended in the same transaction; the event `crossborder.CorridorAvailabilityChanged` follows. |
| `crossborder.CorridorEnableRejected` | **Yes** | A corridor enable proposal was rejected - or withdrawn by its proposer; the corridor stays unavailable. |
| `crossborder.BeneficiaryRegistered` | No | A customer registered a beneficiary abroad (`P9-TSK-017`, ADR-0080 §3) by the corridor provider's reference: the summary names the destination, the issuing rail and the payee check - never a name, a grant or an account identifier. No reason: the customer's own act. The beneficiary is screened by kyc before it can be paid. |
| `crossborder.BeneficiaryRevoked` | No | A customer revoked a beneficiary (`P9-TSK-017`) from whichever non-terminal state it was in - named in the summary, never shown to the customer (tipping-off). No reason: the customer's own act. |
| `crossborder.CrossBorderPaymentAuthorized` | No | A customer authorized a cross-border offer (`P9-TSK-019`, PHASE_9_PLAN.md §12.8): the quote accepted, the total debit held on the source wallet, the cover born and the outbound credit dispatched, in one transaction. The summary names the payment, the quote, the corridor and the routed rail - never an amount, a name or a destination reference. No reason: the customer's own act. The event `crossborder.CrossBorderPaymentInitiated` follows through the outbox. |
| `crossborder.CorridorReportRead` | No | An investigator read a month's corridor report (`P9-TSK-027`, `FX_INVESTIGATE`): volume, fees charged, provider costs and returns per corridor. Every serving is recorded, in the read's transaction; the summary names the month. |
| `crossborder.PaymentTraceRead` | No | An investigator read a cross-border payment's trace (`P9-TSK-027`, `FX_INVESTIGATE`): its identifier chain - quote, payment, outbound credit, entries, expectations, settlement lines and covers. Every serving is recorded, in the read's transaction; the target is the payment. |
| `crossborder.CrossBorderCancellationRequested` | No | A customer requested a cross-border payment's cancellation (`P9-TSK-024`, ADR-0079 point 5): the outbound credit marked for recall - so no re-send ever follows - and the born-once `cancellation_request` recorded, in one transaction. A request, never a conclusion: only the corridor provider's definitive `RECALLED` cancels, recorded by the payment's own failure (`payments.OutboundCreditOutcomeApplied`, `FAILED(RECALLED)`); a refusal as too late leaves the payment to complete. The summary names the payment and what marking the credit found - never an amount, a name or a reference. No reason: the customer's own act. |

### `credit` — `CreditAuditAction`

| Action | Reason required | What it records, and why |
|---|---|---|
| `credit.BureauDataRequested` | No | The platform opened a credit bureau data request for a decision request (`P10-TSK-006`, ADR-0085, `INV-AUD-01`) - written once, by the opener, in the transaction that births the request, under a current lawful basis read in that transaction (`INV-CRD-03`); a platform act on the applicant's behalf, so the actor is the system. The summary names the data request, the decision request, the source kind and the provider - never an attribute or a payload. A retry is the same access under the same reference (one pull at the bureau), recorded as an attempt row, not a second act. |
| `credit.FinancialDataRequested` | No | The platform opened a financial-data request for a decision request (`P10-TSK-007`, ADR-0085, `INV-AUD-01`) - `credit.BureauDataRequested`'s twin for its own source, under its own purpose (`FINANCIAL_DATA_ACCESS`, `INV-CRD-03`): written once by the opener; the summary names the request, the source kind and the provider, never a figure. |
| `credit.ScorecardVersionProposed` | **Yes** | A holder of CREDIT_POLICY_ADMINISTER proposed a whole scorecard model version (`P10-TSK-011`, ADR-0086 §3): its bands born with it in the proposing transaction and immutable from insert by trigger (`INV-CRD-05`), the family's proposals serialised on advisory namespace 10, awaiting a DIFFERENT person's activation (`INV-AUD-04`). |
| `credit.ScorecardVersionActivated` | **Yes** | A different person activated a proposed scorecard model version (`INV-AUD-04`, also held by `credit V006`'s four-eyes `CHECK`), retiring its predecessor in the same transaction - their effective periods meeting on the database clock; the event `credit.ScorecardModelVersionActivated` carries the version, never the bands. |
| `credit.ScorecardVersionRejected` | **Yes** | A proposed scorecard model version was rejected - or withdrawn by its proposer; no model changed. A correction is a rejection and a new proposal. |
| `credit.PolicyVersionProposed` | **Yes** | A holder of CREDIT_POLICY_ADMINISTER proposed a whole credit policy version for a product (`P10-TSK-012`, ADR-0086): complete - a fallback for every source kind it reads (`INV-CRD-10`), every rule well typed with an adverse catalogued reason (`INV-CRD-02`), and a rule guaranteed to stop an approval past its maximum exposure (`INV-CRD-09`; since the Phase 10 exit review, `P10-DOC-001`, 2026-10-09) - its parameters and rules born with it and immutable from insert by trigger (`INV-CRD-05`), awaiting a DIFFERENT person's activation (`INV-AUD-04`). The summary names the product, the version, the rule count and the source kinds - never a threshold. |
| `credit.PolicyVersionActivated` | **Yes** | A different person activated a proposed credit policy version (`INV-AUD-04`, also held by `credit V008`'s four-eyes `CHECK`), retiring the product's predecessor in the same transaction - their effective periods meeting; the event `credit.CreditPolicyVersionActivated` carries the version, never the rules. |
| `credit.PolicyVersionRejected` | **Yes** | A proposed credit policy version was rejected - or withdrawn by its proposer; no policy changed. A correction is a rejection and a new proposal. |
| `credit.DecisionRequestCancelled` | No | The applicant cancelled their own credit decision request before it was evaluated (`P10-TSK-014`; the `fx.QuoteCancelled` precedent) - a customer act, under the request row's lock (lock order element (2)), conditional on `SUBMITTED`, `COLLECTING` or `READY`; written once, in the cancelling transaction beside `credit.CreditDecisionRequestClosed`. The summary names the request and the status it left, never an amount. Submission is not an audit record: the request's row, its history and `credit.CreditDecisionRequested` record it, and the access it leads to is audited by the data request's opener. |
| `credit.DecisionRecorded` | No | A credit decision recorded for a decision request (`P10-TSK-016`, ADR-0087) - by the deciding transaction, profile-first, beside the decision, its reasons, the request's `DECIDED` edge and `credit.CreditDecisionRecorded`; written once, by the acting decider only (ten deciders, one record). The summary names the request, the decision and the outcome, never an amount (`INV-AUD-02`); the platform's decision needs no reason, a person's (`P10-TSK-018`) carries its own act. |
| `credit.DecisionConsumed` | No | An approved credit decision taken up by its consumer through credit's consumption port (`P11-TSK-001`, ADR-0091, `INV-LND-06`) - in the consumer's own transaction, under the party's `credit_profile` row lock, beside the born-once `credit_decision_consumption` row written by `credit.consume_decision`; written once, by the consuming call only (ten consumers, one record; the same consumer's replay writes none; a refusal - not approved, lapsed, already consumed, not found - writes none, its caller rolling back). The actor is whoever the caller's scope names (Phase 11: the accepting customer); the target the decision; the summary names the decision and the consumer's kind and reference, never an amount (`INV-AUD-02`). **Amended by `P12-TSK-001`** (credit `V022`): the kind is `LOAN_AGREEMENT` or `BNPL_AGREEMENT` (Phase 12: the BNPL agreement `bnpl` births at checkout, the accepting customer the actor); a consumer of the other product family - a loan agreement taking up a BNPL decision, or the reverse - is the caller's defect, refused before any lock (`ConsumerOfAnotherFamily`; `check_violation` from `credit.consume_decision`) and writes no record. |
| `credit.ExplanationRead` | No | A holder of `CREDIT_INVESTIGATE` read a decision's full explanation (`P10-TSK-017`, `INV-AUD-01`) - the snapshot's attributes with provenance, the pinned rules and which fired; written in the read's own transaction, which commits before the answer, so no explanation is served without its record. The summary names the decision and the request. |
| `credit.EvidenceRead` | **Yes** | A holder of `CREDIT_INVESTIGATE` read a credit record's raw evidence through `credit.read_evidence` (`P10-TSK-017`, ADR-0085 point 7) with a reason; an attempt its key could not decrypt is recorded `FAILED` and nothing served. The summary names the evidence and the record, never its content. |
| `credit.DecisionReplayed` | **Yes** | A holder of `CREDIT_INVESTIGATE` replayed a decision from its sealed snapshot and pinned policy, scorecard and engine versions, with a reason (`P10-TSK-019`, `INV-CRD-01`): the replay reads one read-only `REPEATABLE READ` snapshot and writes nothing; this row - the verdict and what differed, by kind, never a value - commits in a transaction of its own before the verdict is served. A blank reason is refused, recorded nowhere. |
| `credit.ReviewCasesRead` | No | A holder of `CREDIT_UNDERWRITE` read the review queue (`P10-TSK-018`, ADR-0089 point 8, `INV-AUD-01`) - each case's basis, the snapshot's normalised attributes and the rules the evaluation triggered, never raw evidence; written in the read's own transaction. The target is the status filter; the summary counts the cases served. |
| `credit.ReviewCaseAssigned` | No | A holder of `CREDIT_UNDERWRITE` took an open review case (`P10-TSK-018`, ADR-0089 point 5) - under the request's row lock, then the case's, conditional on an `IN_REVIEW` request; of two underwriters one is recorded. From here the request no longer expires under them. |
| `credit.ReviewCaseReleased` | No | The underwriter holding a review case released it back to the queue (`P10-TSK-018`, G9) - the same two locks; the request's validity governs it again. |
| `credit.ReviewDecided` | **Yes** | The underwriter holding a review case decided it with catalogued reason codes and a reason (`P10-TSK-018`, `INV-CRD-11`): recorded as the decision by the deciding transaction (beside `credit.DecisionRecorded`), or - an approval above the product's four-eyes threshold - recorded on the case, awaiting a second underwriter. Recorded `FAILED` when the deciding transaction found the party's standing or consent lost and closed the request and the case, nothing decided. Never names an amount. |
| `credit.ReviewSecondApproval` | No | A second holder of `CREDIT_UNDERWRITE`, never the first decider (`INV-AUD-04`; `credit V013`'s `CHECK`), approved a case's first decision: the decision recorded in the same transaction with the first decision's content. `FAILED` as `credit.ReviewDecided` when the request was abandoned instead. |
| `credit.ReviewSecondApprovalRefused` | **Yes** | A second holder of `CREDIT_UNDERWRITE`, never the first decider, refused a case's first decision with a reason (`P10-TSK-018`): the case back with its first underwriter, the refused decision kept in the case's history. |
| `credit.ReviewOwnCaseRefused` | No | A holder of `CREDIT_UNDERWRITE` attempted an act - taking, deciding, second-approving or refusing - on a review case whose applicant is their own party (the Phase 10 to 11 transition; `INV-CRD-11`, `INV-AUD-04`): refused `credit.SelfDealingRefused`, nothing changed, and recorded `FAILED` in a transaction of its own after the act's rolled back, so the attempt survives; a retry is recorded as the attempt it is. Its target the case, its detail the act attempted. |
| `credit.ReportRead` | No | A holder of `CREDIT_INVESTIGATE` was served a credit operations report (`P10-TSK-020`): a month's outcomes by pinned policy version with its referrals, its reason-code distribution, or its sources' availability - counts and rates read in one `REPEATABLE READ` snapshot, never an amount, a score, an attribute or a party. One record per serving, committed in the reading's own transaction, its target the report's name and its detail `report=<name>, month=<YYYY-MM>, rows=N`; a refused month or caller writes none. |

`credit.BureauDataRequested` joined at `P10-TSK-006`, `credit.FinancialDataRequested` at `P10-TSK-007`, the three
scorecard acts at `P10-TSK-011`, the three policy acts at `P10-TSK-012`, `credit.DecisionRequestCancelled` at
`P10-TSK-014`, `credit.DecisionRecorded` at `P10-TSK-016`, `credit.ExplanationRead` and
`credit.EvidenceRead` at `P10-TSK-017`, and the six review acts (`credit.ReviewCasesRead`, `credit.ReviewCaseAssigned`,
`credit.ReviewCaseReleased`, `credit.ReviewDecided`, `credit.ReviewSecondApproval`, `credit.ReviewSecondApprovalRefused`)
at `P10-TSK-018`, `credit.DecisionReplayed` at `P10-TSK-019`, `credit.ReportRead` at `P10-TSK-020`,
`credit.ReviewOwnCaseRefused` at the Phase 10 to 11 transition, and `credit.DecisionConsumed` at `P11-TSK-001`.

| Action code | Reason required | Description |
|---|---|---|
| `lending.CapitalContributionProposed` | **Yes** | A holder of `LENDING_CAPITAL_ADMINISTER` proposed a contribution of the platform's own money to lending capital (`P11-TSK-004`, ADR-0096 §4) - currency, amount and the bank reference its transfer will carry - awaiting a DIFFERENT holder's approval (`INV-AUD-04`). Never capital by itself (`INV-LND-13`). The summary names the currency only - never the amount or the reference (`INV-AUD-02`). |
| `lending.CapitalContributionApproved` | **Yes** | A holder of `LENDING_CAPITAL_ADMINISTER` other than its proposer approved a lending-capital contribution (`INV-AUD-04`, also held by `lending V002`'s four-eyes `CHECK` and pinned proposer copy), opening its `LENDING_CAPITAL_CONTRIBUTION` expectation in the same transaction; capital exists only once bank recognition matches the statement line (`INV-LND-13`, `INV-SET-06`). The summary names the currency and the expectation reference. Losers of a race record nothing. |
| `lending.CapitalContributionRejected` | **Yes** | A proposed lending-capital contribution was rejected - or withdrawn by its proposer (`byProposer=true` in the summary); no expectation was opened and no capital can arise from it. |
| `lending.TermsVersionProposed` | **Yes** | A holder of `LENDING_ADMINISTER` proposed a whole new version of a loan product's terms (`P11-TSK-005`, ADR-0092 §1-3) - rate, fees, servicing conventions, engine and template versions, judged complete and within bounds (`lending.TermsInvalid` otherwise, nothing written) and immutable from this moment (`INV-LND-05`) - awaiting a DIFFERENT holder's activation (`INV-AUD-04`). The summary names the product and the version number only - never a rate or a fee (`INV-AUD-02`). |
| `lending.TermsVersionActivated` | **Yes** | A holder of `LENDING_ADMINISTER` other than its proposer activated a loan terms version (`INV-AUD-04`, also held by `lending V003`'s four-eyes `CHECK`), retiring the product's predecessor in the same transaction so one version is in force at every instant; the event `lending.LoanTermsVersionActivated` carries the version. The summary names the product, the activated and the retired version numbers. Losers of a race record nothing. |
| `lending.TermsVersionRejected` | **Yes** | A proposed loan terms version was rejected - or withdrawn by its proposer (`byProposer=true` in the summary); it was never in force and the product is free for a new proposal. |
| `lending.OfferAccepted` | No | A customer accepted their own loan offer from a `MULTI_FACTOR` session (`P11-TSK-012`, A19, ADR-0091 point 4): agreement version 1 committed as an exact copy of the offer's terms, credit's decision consumed (credit's own `credit.DecisionConsumed` beside it, in the same transaction), the loan account and its six ledger accounts opened, exposure and - for a loan - capital committed, all under the party's credit profile lock. Its target the loan; the summary names the offer, the loan, the product and the agreement version - never an amount (`INV-AUD-02`). The acceptance evidence (identity, session, assurance, client address, agent) is its own record, `lending.loan_acceptance`. Losers of a race and refusals record nothing. |
| `lending.LoanDisbursed` | No | The platform disbursed an instalment loan to the borrower's wallet (`P11-TSK-014`, ADR-0097; a system act - the acceptance is the instruction, A7, and no person or operator can disburse): posting rule 1 (`DR LOAN_PRINCIPAL / CR` the wallet, less an origination fee to `LOAN_FEE_INCOME`) under the ledger key `lending.disbursement:<loan>`, the disbursement `POSTED` and the loan `ACTIVE`, the accrual start and schedule version 1 fixed (path W; on path X, `P11-TSK-015`, the hold of the net principal placed and the payout born `PENDING` instead - interest waits for the payout's terminal outcome) - one transaction under the party's credit profile lock (L0) and the loan's row lock (L3). Actor the system (`SystemActorCallSitesAreEnumeratedTest`: `LoanDisbursementSchedule.sweepOnce`), correlation the acceptance's. Its target the loan; the summary names the loan, the journal entry, the value date and the path - never an amount or the wallet (`INV-AUD-02`). Losers of a race and retries record nothing; a cancellation is recorded by the loan's history and `lending.LoanCancelled`, not here. |
| `lending.ReportRead` | No | A holder of `LENDING_INVESTIGATE` was served a lending report - since `P11-TSK-004` the capital report (`GET /v1/operator/lending/capital`): per supported currency the recognised capital, the committed and outstanding principal (loan, line and - since `P12-TSK-005` - BNPL principal, one figure), the headroom, the safeguarding term and the capital proof's verdict, and the most recent contributions with their derived recognition - read in one `REPEATABLE READ` snapshot and committed with this record, so no report is served without it. Amounts are an audited report, never a metric (ADR-0072). Its target the report's name (`capital`), its detail `report=capital, currencies=N, contributions=M, truncated=B`. Since `P11-TSK-030`, also the four operations reports (`GET /v1/operator/reports/lending/{portfolio,delinquency,accrual,capital}?date=`): the loan book by product and state tied to the ledger and the trial balance with the proofs' counts and the accounts they fail, the book in servicing by days-past-due bucket with its due sums, the interest accrued for a date tied to its entries, and the capital's headroom - each as of one business date, read in one read-only `REPEATABLE READ` snapshot, this record committed in a transaction of its own BEFORE the report is answered, so no report is served unrecorded (a date the platform has not had is served empty, and recorded; a malformed date is refused `422` and records nothing). Its target the report's name; its detail `report=<name>, date=<date>, known=B, rows=N` - never an amount (`INV-AUD-02`). |
| `lending.RepaymentReversalProposed` | **Yes** | A holder of `LOAN_SERVICE` proposed the exact reversal of one repayment (`P11-TSK-020`, ADR-0099 point 4) of a loan not of their own party, with a reason screened for card and account numbers (`lending V016`'s twin `CHECK`s beneath), awaiting a DIFFERENT holder of `LOAN_SERVICE_APPROVE` (`INV-AUD-04`, `INV-LND-11`). Nothing is posted by a proposal. Its target the reversal; the summary names the loan and the repayment - never an amount. Losers of a race and refusals record nothing. |
| `lending.RepaymentReversalApproved` | **Yes** | A holder of `LOAN_SERVICE_APPROVE` other than its proposer (also held by `lending V016`'s four-eyes `CHECK`) approved a repayment reversal: posting rule 10 - `ReversalService`'s exact swap of the repayment's rule 5 entry under `lending.repayment-reversal:<reversal>`, the wallet credited back - the allocations negated by rows, what it paid due again, one transaction under the party's credit profile lock (L0), the loan's (L3), the proposal's (L5), the capital row when principal is re-instated (L6) and the wallet's (L7); `lending.RepaymentReversed` beside it. The summary names the loan, the repayment, the reversal entry and the entry it reverses - never an amount. Losers of a race record nothing. |
| `lending.RepaymentReversalRejected` | **Yes** | A proposed repayment reversal was rejected - or withdrawn by its proposer (`byProposer=true` in the summary); nothing was posted and the repayment may be proposed for reversal again. |
| `lending.LoanRead` | No | A holder of `LOAN_SERVICE` read a loan account through `GET /v1/operator/loans/{id}` (`P11-TSK-020`, ADR-0099 point 8): its state, agreement and balances derived from journal lines, its repayments with their derived status and its reversals, read in one `REPEATABLE READ` snapshot and committed with this record, so no read is served without it. Its target the loan; the summary names the loan and the row counts - never an amount. An unknown loan is a `404`, read and recorded as nothing. |
| `lending.SelfDealingRefused` | No | An operator tried to propose, approve or reject a repayment reversal - or, since `P11-TSK-025`, a waiver (`act=waiver:<act>` in the summary) - or, since `P11-TSK-029`, to read a loan's explanation or replay it (`act=investigation:explanation`, `act=investigation:replay` - a lending investigator) - on a loan of their own party - or is no identity the platform can place in a party - and was refused `403 lending.SelfDealingRefused` before anything was written (`P11-TSK-020`, ADR-0099 point 2, the Phase 10 to 11 transition's R1, `INV-LND-11`). Recorded `FAILED` in a transaction of its own after the act rolled back, so the attempt survives (credit's `credit.ReviewOwnCaseRefused` precedent). Its target the loan; the summary names the loan and the act. |
| `lending.SelfApprovalRefused` | No | A proposer tried to approve their own four-eyes proposal - a repayment reversal (`act=repayment-reversal:approve`), a waiver (`act=waiver:approve`), an amendment (`act=amendment:approve`), a loan terms version's activation (`act=terms:activate`) or a lending-capital contribution (`act=capital:approve`) - and was refused `403 lending.SelfApprovalRefused` before anything was written (`INV-AUD-04`, `INV-LND-11`; each table's four-eyes `CHECK` holds it beneath the domain). Recorded `FAILED` in a transaction of its own after the act rolled back, so the attempt survives (the Phase 11 completion gate's R4, A5-01; `lending.SelfDealingRefused`'s shape, credit's `REVIEW_SECOND_APPROVAL_REFUSED` and merchant's `PayoutDestinationApprovalRefused` precedents). Its target the proposal (`repayment_reversal`, `waiver`, `loan_amendment`, `loan_terms_version`, `capital_contribution`); the summary names it, its loan or product where it has one, and the act - never an amount (`INV-AUD-02`). The proposal stays `PROPOSED`. |
| `lending.WaiverProposed` | **Yes** | A holder of `LOAN_SERVICE` proposed a waiver (`P11-TSK-025`, A14, ADR-0099) on a loan not of their own party - a fee's unpaid remainder (`FEE`), a paid fee refunded to the credit balance (`FEE_REFUND`) or a billed item's interest (`INTEREST`) - within what is due now under the loan's row lock, with a reason screened for card and account numbers (`lending V021`'s twin `CHECK`s beneath), awaiting a DIFFERENT holder of `LOAN_SERVICE_APPROVE` (`INV-AUD-04`, `INV-LND-11`). Nothing is posted by a proposal. Its target the waiver; the summary names the loan, the kind, the billed item and the assessment - never an amount. |
| `lending.WaiverApproved` | **Yes** | A holder of `LOAN_SERVICE_APPROVE` other than its proposer (also held by `lending V021`'s four-eyes `CHECK`) approved a waiver, bounded again by what is due now: posting rule 8 (`ReversalService` of the fee's rule 4 entry under `lending.waiver:<waiver>`, bounded by it - `INV-REV-02`), 8a (`DR LOAN_FEE_INCOME / CR LOAN_CREDIT_BALANCE`) or 9 (`DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE`), and the account closed in the same transaction when it settled the last receivable (`INV-LND-08`). The summary names the loan, the kind, the item, the journal entry and any closure - never an amount. |
| `lending.WaiverRejected` | **Yes** | A proposed waiver was rejected - or withdrawn by its proposer (`byProposer=true` in the summary); nothing was posted and the fee or interest may be proposed for waiver again. |
| `lending.AmendmentProposed` | **Yes** | A holder of `LOAN_SERVICE` proposed amending an `ACTIVE` instalment loan's governing agreement version (`P11-TSK-027`, ADR-0099 point 5) on a loan not of their own party - a rate never above it, a remaining term never shorter than its schedule has left and within the product's, arrears re-scheduled or not - with a reason screened for card and account numbers (`lending V023`'s twin `CHECK`s beneath), awaiting a DIFFERENT holder of `LOAN_SERVICE_APPROVE` and then the customer (`INV-AUD-04`, `INV-LND-11`). No amount is named: never more credit. Its target the amendment; the summary names the loan, the version amended, the remaining instalments and the arrears flag - never the rate. |
| `lending.AmendmentApproved` | **Yes** | A holder of `LOAN_SERVICE_APPROVE` other than its proposer (also held by `lending V023`'s four-eyes `CHECK`) approved an amendment while the agreement version it amends was still the loan's and the loan `ACTIVE`, opening the customer's offer until its expiry (the pinned offer validity on the database clock). Nothing is posted and the agreement is unchanged until the customer accepts. The summary names the loan and the version. Losers of a race record nothing. |
| `lending.AmendmentRejected` | **Yes** | A proposed amendment was rejected - or withdrawn by its proposer (`byProposer=true` in the summary); the agreement is unchanged and a new amendment may be proposed. |
| `lending.AmendmentAccepted` | No | The customer accepted an approved, unexpired amendment of their own loan from a `MULTI_FACTOR` session (`P11-TSK-027`, scenario 8): agreement version n+1 (the amended rate and term, the same principal - canonical terms format 3), its acceptance evidence (`lending.loan_acceptance`, binding the amendment's document hash), the arrears re-scheduled by posting rule 13 (`DR LOAN_PRINCIPAL / CR LOAN_PRINCIPAL_DUE`, `lending.amendment:<amendment>`) when it said so, and schedule version n+1 from the principal outstanding - one transaction under the loan's row lock, conditional on the agreement version it amends (`INV-LND-05`). Its target the amendment; the summary names the loan, the agreement and schedule versions and the rule 13 entry - never an amount or the rate. A converging retry records nothing. |
| `lending.ExplanationRead` | No | A holder of `LENDING_INVESTIGATE` was served a loan account's explanation through `GET /v1/operator/loans/{id}/explanation` (`P11-TSK-029`, PHASE_11_PLAN.md §9): its agreement versions and hashes, schedule versions, accruals, billings, statements, draws, repayments with their allocations, credit-balance applications, fees, waivers, reversals, amendments and re-scheduled arrears, conditions, the payout, the closure, every journal entry on its six accounts with the row that names it, and the subledger proof's six figures and verdict - from rows alone, in one `REPEATABLE READ` transaction committed with this record, so no explanation is served without it. Never of the investigator's own party's loan (`lending.SelfDealingRefused`, `act=investigation:explanation`). Its target the loan; the summary names the loan, the row counts and the verdict - never an amount (`INV-AUD-02`). An unknown loan is a `404`, read and recorded as nothing. |
| `lending.LoanReplayed` | **Yes** | A holder of `LENDING_INVESTIGATE` replayed a loan account through `POST /v1/operator/loans/{id}/replay` (`P11-TSK-029`, ADR-0092 point 9) with a reason (required, screened for card and account numbers - `422 lending.ReasonRequired` before anything is read): every agreement version's canonical terms re-rendered in their stored format and their hash re-verified, every schedule version, accrual, billing, statement, credit-balance application, allocation and late fee re-derived by the engine version its row pinned, on one read-only `REPEATABLE READ` snapshot - `IDENTICAL` or `DIVERGED`, nothing changed. Recorded in a transaction of its own that commits before the verdict is answered. Never of the investigator's own party's loan (`lending.SelfDealingRefused`). Its target the loan; the summary names the loan, the verdict, the facts replayed and the kinds that diverged - never an amount (`INV-AUD-02`). |

The four lending actions joined at `P11-TSK-004` - the module's first audited acts - and the three terms acts at
`P11-TSK-005`; the repayment reversal's three acts, the operator's loan read and the self-dealing refusal at `P11-TSK-020`;
the waiver's three acts at `P11-TSK-025`; the amendment's four acts at `P11-TSK-027` (and `lending.SelfDealingRefused` names
`act=amendment:<act>` since); the investigator's explanation and replay at `P11-TSK-029` (and `lending.SelfDealingRefused`
names `act=investigation:explanation` or `act=investigation:replay` since).

### `bnpl` — `BnplAuditAction`

| Action code | Reason required | Description |
|---|---|---|
| `bnpl.TermsVersionProposed` | **Yes** | A holder of `BNPL_ADMINISTER` proposed a whole new version of a BNPL product's terms through `POST /v1/operator/bnpl/terms` (`P12-TSK-004`, ADR-0102 decision 4) - plans, rate (pay-in-N; pay-in-4's held to zero), late fee and cap, allocation, delinquency bounds, collection cadence, the offer validity cap, the dispute window and threshold, the payoff horizon, engines and template - judged complete and within bounds (`422 bnpl.TermsInvalid`) and schedulable across credit's amount and plan bounds (`422 bnpl.TermsNotAmortising`) before anything is written, and immutable from this moment (`INV-HIST-04`), awaiting a DIFFERENT holder's activation (`INV-AUD-04`). The summary names the product and the version number only - never a rate or a fee (`INV-AUD-02`). |
| `bnpl.TermsVersionActivated` | **Yes** | A holder of `BNPL_ADMINISTER` other than its proposer activated a BNPL terms version (`INV-AUD-04`, also held by `bnpl V002`'s four-eyes `CHECK`), retiring the product's predecessor in the same transaction so one version is in force at every instant; the event `bnpl.BnplTermsVersionActivated` carries the version. The summary names the product, the activated and the retired version numbers. Losers of a race record nothing. |
| `bnpl.TermsVersionRejected` | **Yes** | A proposed BNPL terms version was rejected - or withdrawn by its proposer (`byProposer=true` in the summary); it was never in force and the product is free for a new proposal. |
| `bnpl.SelfApprovalRefused` | No | A proposer tried to approve their own BNPL proposal - a terms version's activation (`act=terms:activate`), or since `P12-TSK-024` a repayment reversal's or a waiver's approval (`act=repayment-reversal:approve`, `act=waiver:approve`) - and was refused `403 bnpl.SelfApprovalRefused` before anything was written (`INV-AUD-04`; `bnpl V002`'s four-eyes `CHECK` beneath). Recorded `FAILED` in a transaction of its own after the act rolled back, so the attempt survives (the Phase 11 completion gate's R4, applied from bnpl's first door). Its target the proposal (`bnpl_terms_version`); the summary names it, its product and the act - never an amount (`INV-AUD-02`). The proposal stays `PROPOSED`. *(P12-DOC-001, 2026-10-22: since `P12-TSK-023` also the approval of a dispute resolution above the pinned four-eyes threshold (`act=dispute resolution approval`, `BnplDisputes#recordSelfApproval`; `bnpl V015`'s `bnpl_dispute_proposal_four_eyes` `CHECK` beneath). The target is the proposal of its door: `bnpl_terms_version`, `bnpl_repayment_reversal`, `bnpl_waiver` or `bnpl_dispute_proposal`.)* |
| `bnpl.ApplicationSubmitted` | No | A customer, from a `MULTI_FACTOR` session, applied for a BNPL product and plan inside an open checkout session (`POST /v1/me/bnpl-applications`, keyed `bnpl.application:CUSTOMER:<id>`, `P12-TSK-010`) - judged in good standing, the session open, unexpired and within credit's bounds, its merchant BNPL-enabled and covering its fee, the product's terms `ACTIVE` - and credit's decision request was opened from the BNPL origin for exactly the session's gross in the same transaction (credit's own `credit.CreditDecisionRequested` event beside it). Its target the application; the summary names the product, the plan, the session and credit's request - never an amount or a token (`INV-AUD-02`). An act the plan's §11 list omits, added because every customer act that opens credit is audited. |
| `bnpl.ApplicationWithdrawn` | No | A customer withdrew their own BNPL application while it awaited a decision (`POST /v1/me/bnpl-applications/{id}/withdrawal`, keyed, the platform's conditional step-up, `P12-TSK-010`) - the application locked (B1) and moved `WITHDRAWN`, and credit's request cancelled from the BNPL origin under credit's own row lock in the same transaction (credit's `credit.DecisionRequestCancelled` record beside it). Refused, with nothing written, once credit has evaluated the request. Its target the application; the summary names it and credit's request. |
| `bnpl.OfferDeclined` | No | A customer declined their own BNPL offer (`POST /v1/me/bnpl-offers/{id}/decline`, keyed `bnpl.offer-decline:CUSTOMER:<id>`, the platform's conditional step-up, `P12-TSK-011`) - the offer locked (B2) and moved `DECLINED` only while `OFFERED` and before its `expires_at` on the database clock, the complement of the sweep's expiry, so exactly one of `DECLINED` and `EXPIRED`. Nothing is released: credit's unconsumed approval lapses at its own validity, and the checkout session stays `OPEN` for another instrument. Its target the offer; the summary names it and its application - never an amount. The origination sweep's own facts (the offer, the decline, the closures, the expiry) are events and history rows, not audit records: no person acts. |
| `bnpl.OfferAccepted` | No | A customer accepted their own BNPL offer from a `MULTI_FACTOR` session (`POST /v1/me/bnpl-offers/{id}/acceptance`, keyed `bnpl.offer-acceptance:CUSTOMER:<id>`, the offer's terms hash echoed, `P12-TSK-013`) - the purchase: under the party's credit profile lock (L0) credit's decision consumed for the agreement (credit's own `credit.DecisionConsumed` beside it, in the same transaction), the capital judged under its row lock (L6), agreement version 1 committed as an exact copy of the offer's terms with its evidence (`bnpl.bnpl_acceptance`), the purchase, the obligation, schedule version 1 and its six ledger accounts born, the merchant financed (`bnpl.MerchantFinanced`) and - pay-in-4 - instalment 1 billed and collected from the wallet, and the checkout session completed by financing with its order, all in one transaction. Its target the purchase; the summary names the offer, the agreement and its version, the purchase, the product and the plan - never an amount (`INV-AUD-02`). Refusals and the losers of a race record nothing. |
| `bnpl.MerchantFinanced` | No | A BNPL purchase financed its merchant (`P12-TSK-013`, owner decision B2): the merchant's `MERCHANT_PAYABLE` credited the gross less the merchant discount priced by its pinned BNPL fee schedule version, `BNPL_PRINCIPAL` debited the gross and `BNPL_MERCHANT_FEE_INCOME` credited the fee by the entry F1 (`bnpl.financing:<purchase>`), funded from lending capital - in the acceptance's transaction. Recorded under the accepting customer as actor, the act that commits the financing (the plan's "system" actor refined: `SystemActorRulesTest` forbids `Actor.SYSTEM` outside `SecurityContext`, and an authenticated request is not a site where the platform may claim to act). Its target the purchase; the summary names the purchase, the financing entry and the fee schedule version - never an amount or the merchant's figures. |
| `bnpl.RefundApplied` | **Yes** | A BNPL purchase was refunded (`P12-TSK-021`; owner decisions B3 and B7, ADR-0105) - by its merchant over an API key carrying the `BNPL_REFUND` scope (`POST /v1/merchant/bnpl-purchases/{id}/refunds`, keyed `bnpl.refund:MERCHANT:<merchant>`) or by an operator holding `PAYMENT_REFUND` (`POST /v1/operator/bnpl-purchases/{id}/refunds`, keyed `bnpl.refund:<actor type>:<id> (CUSTOMER-typed for an operator session today)`), with a required reason (`RESTRICTED-PII`, screened): under the purchase's lock the refund bounded by what remains of it (`INV-BNPL-07`), under the obligation's the unpaid instalments cancelled latest first and the excess returned to the customer's wallet (or held as credit, OQ-13), the merchant fee returned by the cumulative method and the payable debited the refund's net - below zero after a payout by at most the purchase's financed net (B7, `INV-MER-07` as amended) - in one entry (`bnpl.refund:<refund>`), with the schedule and agreement versions it made and any closure, in one transaction. Actor the merchant (the key id in the summary) or the operator; target the refund (`bnpl_refund`); the summary names the purchase, the door, the key id and the agreement version - never an amount (`INV-AUD-02`). Refusals write nothing (the scope's and the self-dealing's are recorded below). |
| `bnpl.RefundScopeRefused` | No | A merchant's API key not issued with the `BNPL_REFUND` scope tried to refund a BNPL purchase and was refused `403 bnpl.RefundScopeRequired` before anything was locked or read (`P12-TSK-021`; a key issued before `merchant V011` carries none - fails closed). Recorded `FAILED` in a transaction of its own, so the attempt survives. Target the purchase the path named (`purchase`, unconfirmed - nothing was read); the summary names the key id. *(The Phase 12 → 13 transition gate, 2026-10-22, A5-F1/F6: also a scope-less key's `ACCEPT` of a BNPL dispute, which refunds - target type `bnpl_dispute`, the dispute the path named; summary `dispute acceptance refused - key=<key id> carries no BNPL_REFUND scope`.)* |
| `bnpl.SelfDealingRefused` | No | An operator tried to refund, at the operator's BNPL refund door, a purchase whose customer is the operator's own party (`P12-TSK-021`; the self-dealing rule) - or to resolve, propose, approve or reject the resolution of a BNPL dispute of their own party (`P12-TSK-023`) - or to propose, approve or reject a repayment reversal or a waiver on an agreement of their own party, or as an operator the platform cannot place in a party (fail closed; `P12-TSK-024`) - and was refused `403 bnpl.SelfDealingRefused` with nothing written. Recorded `FAILED` in a transaction of its own after the act rolled back. Target the purchase, the dispute or the agreement; the summary names it and the act - never an amount. *(P12-DOC-001, 2026-10-22: since `P12-TSK-025` also the investigator's read, explanation and replay of an agreement of their own party or as an operator the platform cannot place (`act=investigation:<read|explanation|replay>`, fail closed; target the agreement). As built, fail-closed holds only at the corrections and the investigation (`BnplRepaymentReversals.judgeSelfDealing`): at the operator refund door (`BnplRefunds`) and at the dispute's operator acts (`BnplDisputes.refuseSelfDealing`) an operator the platform cannot place in a party is NOT refused - only one placed in the purchase's or dispute's own party is; and the operator's dispute read (`bnpl.DisputeRead`, `BnplDisputes.investigate`) carries no self-dealing check at all. Both are open for the owner.)* *(The Phase 12 → 13 transition gate's repair R1, 2026-10-22 - FINDINGS A7-6/A5-F3 (dispute half) and A5-F4: the dispute's operator acts now fail closed - an operator the platform cannot place in a party is refused - and the dispute read (`act=dispute read`) and the dispute queue (`act=dispute queue read`, target type `bnpl_dispute_queue`, target the status read; refused only when the operator cannot be placed) are judged too, each recorded `FAILED` in a transaction of its own. The operator refund door's fail-closed half is R2's.)* *(The Phase 12 → 13 transition gate, 2026-10-22: the operator refund door now fails closed - an operator the platform cannot place in a party is refused (A7-6); and the MERCHANT doors that move money judge the key's issuer - a merchant refund or a dispute's `ACCEPT` through a key whose `issued_by` is the purchase's own party, or cannot be placed in one, is refused and recorded here: actor the merchant, target the purchase, summary `purchase=<id>, act=<refund|acceptance> refused - the key's issuer ..., key=<key id>` - never the issuer (A5-F1).)* |
| `bnpl.RepaymentReversalProposed` | **Yes** | A holder of `BNPL_SERVICE` proposed, with a reason, the exact reversal of one repayment of an `ACTIVE` BNPL agreement (`P12-TSK-024`; `POST /v1/operator/bnpl-agreements/{id}/repayments/{rid}/reversal`, keyed `bnpl.servicing:<actor type>:<id>`) - under the obligation's lock, one live proposal per repayment, never a payoff's, its held credit still held; nothing posted. Target the reversal; the summary names the agreement and the repayment - never an amount. |
| `bnpl.RepaymentReversalApproved` | **Yes** | A holder of `BNPL_SERVICE_APPROVE` other than its proposer approved a BNPL repayment reversal (`INV-AUD-04`; `bnpl V016`'s four-eyes `CHECK` beneath), naming the proposal: under L0, B4, B6, L6 (principal re-instated) and (P), F8 - the ledger's `ReversalService` swapping the repayment's F3 entry exactly under `bnpl.repayment-reversal:<reversal>`, valued `max(today, floor)` - the allocation negated, the amount due again, the wallet credited back, with `bnpl.BnplRepaymentReversed`, in one transaction. Target the reversal; the summary names the agreement, the repayment and both entries - never an amount. |
| `bnpl.RepaymentReversalRejected` | **Yes** | A proposed BNPL repayment reversal was rejected by a holder of `BNPL_SERVICE` - or withdrawn by its proposer (`P12-TSK-024`); nothing was posted and the repayment may be proposed again. |
| `bnpl.WaiverProposed` | **Yes** | A holder of `BNPL_SERVICE` proposed, with a reason, waiving an amount of one late fee of an `ACTIVE` BNPL agreement (`P12-TSK-024`; `POST /v1/operator/bnpl-agreements/{id}/waivers`, keyed `bnpl.servicing:<actor type>:<id>`) - within what may still be forgiven (its unpaid remainder, never what was paid, released or waived: the Phase 11 gate's R3), one undecided per fee; nothing posted. Target the waiver; the summary names the agreement and the assessment - never an amount. |
| `bnpl.WaiverApproved` | **Yes** | A holder of `BNPL_SERVICE_APPROVE` other than its proposer approved a BNPL fee waiver (`INV-AUD-04`; `bnpl V016`'s four-eyes `CHECK`): the bound re-judged under B4, F7 - the ledger's `ReversalService` of the fee's F4 entry for the amount (`DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE`, `bnpl.waiver:<waiver>`) - and the closure when it zeroed the last receivable, with `bnpl.BnplFeeWaived`, in one transaction. Target the waiver; the summary names the agreement, the assessment and the entry - never an amount. |
| `bnpl.WaiverRejected` | **Yes** | A proposed BNPL fee waiver was rejected by a holder of `BNPL_SERVICE` - or withdrawn by its proposer (`P12-TSK-024`); nothing was posted and the fee may be proposed again. |
| `bnpl.DisputeOpened` | **Yes** | A customer contested their own BNPL purchase with the platform (`P12-TSK-023`, ADR-0106; `POST /v1/me/bnpl-agreements/{id}/disputes`, keyed `bnpl.dispute:CUSTOMER:<id>`, the platform's conditional step-up) with a reason category and a reason (`RESTRICTED-PII`, screened): under the purchase's lock (B5) the amount bounded by what remains of it (`INV-BNPL-07`), under the obligation's (B4) the instalments a refund of it would cancel - latest first - held from auto-collection, days past due and late fees, and the merchant's response deadline stamped on the database clock (the pinned window, OQ-10). Target the dispute (`bnpl_dispute`); the summary names the purchase, the category, the deadline and the held instalments' count - never an amount (`INV-AUD-02`). Refusals write nothing. |
| `bnpl.DisputeResponded` | **Yes** | A merchant answered a dispute of its BNPL purchase over its own API key, tenant-scoped (`P12-TSK-023`; `POST /v1/merchant/bnpl-disputes/{id}/response`, keyed `bnpl.dispute-response:MERCHANT:<merchant>`) before the response deadline on the database clock, with a statement (`RESTRICTED-PII`, screened) as its reason: `CONTEST` (the dispute `MERCHANT_RESPONDED`, an operator resolves) or `ACCEPT` (resolved as a refund at once - `bnpl.DisputeResolved` and `bnpl.RefundApplied` beside it). Actor the merchant; target the dispute; the summary names the decision and the key id. |
| `bnpl.DisputeResolved` | No | A BNPL dispute was resolved (`P12-TSK-023`, ADR-0106): as a refund - by the merchant's acceptance, by its silence past the response deadline (actor `SYSTEM`, the deadline sweep `BnplDisputeSchedule`) or by an operator holding `BNPL_DISPUTE_RESOLVE` - executed as a `BnplRefund` (door `DISPUTE`) of exactly the disputed amount under the purchase's one bound in the same transaction (`INV-BNPL-08`); or upheld by an operator, the hold lifted and nothing re-billed. Above the pinned four-eyes threshold an operator's resolution is a proposal another operator approved (the approver the actor). Target the dispute; the reason the operator's (none for the merchant's and the system's); the summary names the outcome, the resolver's door and any refund - never an amount. |
| `bnpl.DisputeWithdrawn` | No | A customer withdrew their own open BNPL dispute (`P12-TSK-023`; `POST /v1/me/bnpl-disputes/{id}/withdrawal`, keyed `bnpl.dispute-withdrawal:CUSTOMER:<id>`): the hold lifted - each held instalment's days past due and late-fee day counting from its due date as if never held *(the Phase 12 → 13 gate's A5-F2, `bnpl V018`, 2026-10-22: a withdrawal no longer restarts the clock - until then they counted from the withdrawal's business date, which let an open-and-withdraw loop suppress default)* - and nothing posted. An act the plan's §11 list omits, added because every customer act on a dispute is audited. Target the dispute; the summary names the purchase and the state it left. |
| `bnpl.DisputeResolutionProposed` | **Yes** | An operator holding `BNPL_DISPUTE_RESOLVE` proposed the resolution - a refund or upheld - of a BNPL dispute the merchant answered whose amount is above the pinned four-eyes threshold (`P12-TSK-023`, ADR-0106 decision 5; `POST /v1/operator/bnpl-disputes/{id}/resolution`, keyed `bnpl.servicing:<actor type>:<id>`), with a reason; another holder approves (`…/resolution/approval`) or rejects it. Target the proposal (`bnpl_dispute_proposal`); the summary names the dispute and the outcome proposed. A self-approval is `bnpl.SelfApprovalRefused` (act `dispute resolution approval`). |
| `bnpl.DisputeResolutionRejected` | **Yes** | An operator holding `BNPL_DISPUTE_RESOLVE` rejected - or, its proposer, withdrew - a proposed resolution of a BNPL dispute (`P12-TSK-023`; `POST /v1/operator/bnpl-disputes/{id}/resolution/rejection`), with a reason; the dispute awaits a new proposal. Target the proposal; the summary names the dispute and whether the proposer withdrew it. |
| `bnpl.AgreementRead` | No | A holder of `BNPL_INVESTIGATE` read a BNPL agreement (`P12-TSK-025`; `GET /v1/operator/bnpl-agreements/{id}`): its latest version, its obligation's status and the purchase - every serving recorded in the read's own `REPEATABLE READ` transaction, never the investigator's own party's agreement nor by one the platform cannot place (`bnpl.SelfDealingRefused`, fail closed - unlike lending's operator read, Phase 11 owner item #18, not repeated). Target the agreement; the summary names versions, counts and the subledger verdict - never an amount. |
| `bnpl.ExplanationRead` | No | A holder of `BNPL_INVESTIGATE` was served a BNPL agreement's explanation (`P12-TSK-025`; `GET /v1/operator/bnpl-agreements/{id}/explanation`): every agreement and schedule version, accrual, billing, application, repayment, collection attempt, refund and adjustment, dispute, condition, fee, waiver, reversal, the payoff, the closure and every journal entry with the row naming it - from rows alone - and its subledger proof. Recorded in the read's own transaction; the explanation carries amounts and is never logged (`INV-AUD-02`). Target the agreement; the summary names counts and the verdict. |
| `bnpl.Replayed` | **Yes** | A holder of `BNPL_INVESTIGATE` replayed a BNPL agreement (`P12-TSK-025`; `POST /v1/operator/bnpl-agreements/{id}/replay`) with a reason (required, screened - `422 bnpl.ReasonRequired` before anything is read): every figure re-derived by its pinned engines on one read-only snapshot, `IDENTICAL` or `DIVERGED`, recorded in a transaction of its own before the verdict is answered. Target the agreement; the summary names the verdict, the count replayed and the kinds that diverged - never a figure. |
| `bnpl.DisputeRead` | No | An operator holding `BNPL_DISPUTE_RESOLVE` read a BNPL dispute (`P12-TSK-023`; `GET /v1/operator/bnpl-disputes/{id}`): the customer's reason and the merchant's statement (`RESTRICTED-PII`), the amount, the hold and any proposal - every access recorded (`INV-DSP-03` by analogy, ADR-0106 decision 4). Target the dispute; the summary names its status. The merchant's read of its own open disputes (`GET /v1/merchant/bnpl-disputes`) is not audited - its own record over its own key, never the customer's text. *(The Phase 12 → 13 gate's A5-F4, 2026-10-22: never a dispute of the operator's own party, nor by an operator the platform cannot place in a party - `403 bnpl.SelfDealingRefused`, recorded `FAILED` in a transaction of its own, nothing read.)* |
| `bnpl.DisputeQueueRead` | No | An operator holding `BNPL_DISPUTE_RESOLVE` read a page of the BNPL dispute queue (the Phase 12 → 13 transition gate's A7-2, ADR-0106's promised dispute report delivered as the queue; `GET /v1/operator/bnpl-disputes?status=` `MERCHANT_RESPONDED` or `OPEN`, `&after=<id>` for the next page): at most a hundred disputes in the status, oldest first by the instant each entered it, after the cursor - their figures, deadlines and instants, never the customer's reason or the merchant's statement, and never a dispute of the operator's own party (an operator the platform cannot place in a party is refused `bnpl.SelfDealingRefused`, fail closed). Written in the read's own transaction, so no page is served unrecorded (credit's `credit.ReviewCasesRead` shape). Target type `bnpl_dispute_queue`, target the status read; the summary counts the disputes served, names the cursor and whether more remain - never an amount. |
| `bnpl.ReportRead` | No | A holder of `BNPL_INVESTIGATE` was served a BNPL operations report as of a business date (`P12-TSK-026`; `GET /v1/operator/reports/bnpl/{portfolio,merchant-financing,refunds,delinquency}?date=`): outstanding by product and currency tied to the BNPL book and the trial balance with the three proofs, merchant financing exposure tied to the BNPL-keyed lines of the merchant payables, refund impact on outstanding tied to each refund's entry, delinquency sums from the ledger - each read in one read-only `REPEATABLE READ` snapshot, this record committed in a transaction of its own before the answer (lending's `lending.ReportRead` shape), so no report is served unrecorded; a malformed or missing date is `422 api.ValidationFailed` before any read and unrecorded; a date the platform has not had answers empty (`known=false`) and is recorded. Amounts are an audited report, never a metric (ADR-0072). Target the report; the summary names the report, its date, whether it is reportable and its row count - never an amount, a party, a merchant or an obligation. |

The four bnpl actions joined at `P12-TSK-004` - the module's first audited acts; the application's two at `P12-TSK-010`;
the offer's decline at `P12-TSK-011`; the refund's three at `P12-TSK-021`; the dispute's seven at `P12-TSK-023`; the corrections' six at `P12-TSK-024`
(`bnpl.SelfApprovalRefused` and `bnpl.SelfDealingRefused` widened to them); the investigator's three at `P12-TSK-025`
(`bnpl.SelfDealingRefused` widened to them, act `investigation:<read|explanation|replay>`); the operations reports' one at `P12-TSK-026`; later tasks add theirs here
(`PHASE_12_PLAN.md` §11).
*(P12-DOC-001, 2026-10-22: the list above omitted the acceptance's two at `P12-TSK-013` - `bnpl.OfferAccepted` and
`bnpl.MerchantFinanced` - and `P12-TSK-023`'s widening of `bnpl.SelfApprovalRefused` (dispute resolution approval) and
`bnpl.SelfDealingRefused` (the dispute's operator acts). 29 bnpl actions in all, the count `BnplAuditAction` declares.)*
*(The Phase 12 → 13 transition gate's repair R1, 2026-10-22: the dispute queue's one, `bnpl.DisputeQueueRead` (A7-2), and
`bnpl.SelfDealingRefused` widened to the dispute read and the queue (A5-F4, A7-6). 30 bnpl actions in all.)*

**Not audited: the merchant's read of its own BNPL purchase** (`P12-TSK-014`, `GET /v1/merchant/bnpl-purchases/{id}`).
A merchant reading its own record over its own key is the ordinary use of the surface, as its payable and transaction
reads are, and none of those writes a record; the operator's BNPL reads (`bnpl.AgreementRead`, `bnpl.ReportRead`, later
tasks') are audited because an operator reads across tenants by permission. The read writes nothing and decides nothing.

### `risk` — `RiskAuditAction` *(planned by the Phase 12 → 13 transition, 2026-10-22; declared per task from `P13-TSK-001`, emitted by the tasks named)*

*Declared per task* (`P13-TSK-001`, the `BnplAuditAction` precedent): `RiskAuditAction` holds the two refusals of the
role split - `risk.SelfApprovalRefused` and `risk.SelfDealingRefused`, the two catalogue rows below without a marker,
each named in `AuditCompletenessTest`'s deliberate absences with the task that first raises it (`-002`; `-007`, whose
profile read raised `risk.SelfDealingRefused` before `-016`'s case doors) until it did - and
every later act arrives with the task that raises it, dropping its *planned* marker then. A row that still opens with
the marker is not a catalogue row: `AuditableActionRegistryTest` catalogues a line only when it opens with a backticked
`module.Action` code. The list is `PHASE_13_PLAN.md` §11's: each with actor, time, target, reason where required, correlation
and outcome; summaries carry counts and codes, never an amount, a name, a score or a rule's parameters (`INV-AUD-02`,
`INV-RSK-08`); every refusal of a self-approval or of self-dealing is recorded `FAILED` in a transaction of its own after
the act rolled back (the Phase 11 gate's R4, bnpl's shape). The reason column is the design's reading of §4 - a person's
discretionary act requires one, an automatic consequence does not - for `-001` to confirm.

| Action code | Reason required | Description |
|---|---|---|
| `risk.PolicyProposed` | **Yes** | A holder of `RISK_POLICY_ADMINISTER` proposed a risk policy version for one operation (`POST /v1/operator/risk/policies`, keyed `risk.policy:<actor>`, `P13-TSK-002`; ADR-0109): rules as data of closed kinds with typed parameters, weights, score bands, fail-safe bands (C1), the challenge assurance, the override threshold (C7) and effective-from - judged whole at the door (`422 risk.PolicyInvalid`), immutable from this moment and awaiting a DIFFERENT holder's activation. The summary names the operation and the version - never a threshold or a weight. |
| `risk.PolicyActivated` | **Yes** | A holder of `RISK_POLICY_ADMINISTER` other than its proposer activated a risk policy version from a `MULTI_FACTOR` session (`…/{id}/activation`; `INV-RSK-05`, `INV-AUD-04`, the four-eyes `CHECK` beneath), retiring the operation's predecessor in the same transaction under advisory namespace `13` so the pin never reads "neither"; the event `risk.RiskPolicyActivated` beside it. The summary names the operation and both versions. |
| `risk.PolicyRejected` | **Yes** | A proposed risk policy version was rejected - or withdrawn by its proposer; it was never in force. |
| `risk.PolicyRetired` | No | A risk policy version left force - retired beside its successor's activation in the activation's transaction (the activator the actor), never on its own; every assessment that pinned it still replays under it. |
| `risk.AssessmentRead` | No | A holder of `RISK_INVESTIGATE` read a sealed risk assessment with its inputs, rule hits, score, decision and reason codes (`GET /v1/operator/risk/assessments/{assessmentId}`, `P13-TSK-004`; `-023`'s reports next) - after the self-dealing judgement, recorded in the read's own transaction so nothing is served unrecorded, never one on the reader's own party (`risk.SelfDealingRefused`, fail closed). Its target the assessment (`risk_assessment`); the summary its operation, its decision and whether its seal holds (`seal=HOLDS|BROKEN`) - never an amount, a subject, a score or a rule's parameters. *(Emitted from `P13-TSK-004`, 2026-10-23.)* *(`P13-TSK-012`: raised too by the risk officer's override desk under `RISK_OVERRIDE` - `GET …/assessments/{assessmentId}/overrides[/{overrideId}]`, the overridden assessment served with its overrides, the summary `door=override` and the overrides' count; roles are a partition, so the officer's read is its own door.)* |
| `risk.Replayed` | **Yes** | A holder of `RISK_INVESTIGATE` replayed a sealed risk assessment from its sealed inputs under its pinned policy version and engine (`POST /v1/operator/risk/assessments/{assessmentId}/replay`, a reason required and screened, `P13-TSK-004`): `IDENTICAL` or `DIVERGED`, on one read-only `REPEATABLE READ` snapshot, recorded in a transaction of its own before the verdict is answered (`INV-RSK-01`; `bnpl.Replayed`'s shape). Its target the assessment; the reason the analyst's; the summary the verdict and the kinds that diverged (`verdict=IDENTICAL, diverged=none`) - never a figure. *(Emitted from `P13-TSK-004`, 2026-10-23.)* |
| `risk.OverrideProposed` | **Yes** | A holder of `RISK_OVERRIDE` proposed, with a reason code of the closed `OverrideReason` vocabulary and a text, an override of a `DENY`, `HOLD` or `REVIEW` decision (`POST /v1/operator/risk/assessments/{assessmentId}/overrides`, keyed `risk.override:<actor>`, `P13-TSK-012`; ADR-0108 decision 7): the effect its approval will issue fixed now - a held withdrawal released or denied, a held card payment captured or voided, or for a refused command a `RESUBMISSION` that dispatches nothing - and C7's four eyes fixed with it (`requires_second_person`, derived again by `risk V009`'s birth rule); one standing override per assessment; the decision itself is never edited, and an `ALLOW` or a `CHALLENGE` is never overridden (`409 risk.ActionNotProposable`). The summary names the assessment, the decision, the effect, the reason code and whether a second holder is required - never an amount, a party or the text. |
| `risk.OverrideApproved` | **Yes** | A holder of `RISK_OVERRIDE` approved an override from a `MULTI_FACTOR` session (`…/overrides/{overrideId}/approval`, `P13-TSK-012`; C7 - a holder other than its proposer at or above the pinned version's threshold (EUR 1,000) or for any `DENY`, the four-eyes `CHECK` beneath; below it the proposer's own reasoned approval completes it, recorded `byProposer=true`), recording the action it issues under a permit and announcing it `risk.RiskActionRequested` - the action dispatched only after the approval commits, through payments' keyed door under `risk.action:<override id>` (and re-driven by the door's retry and the monitoring sweep's override leg); a `RESUBMISSION` issues nothing. The summary names the assessment, the effect, whether a second holder was required and whether the proposer approved - never an amount. |
| `risk.OverrideRejected` | **Yes** | A proposed override was rejected - or withdrawn by its proposer (`…/overrides/{overrideId}/rejection`, `P13-TSK-012`); it issues nothing and the decision stands (a held movement keeps its hold until its expiry, a later override or a disposition). The summary names the assessment, the effect and whether the proposer withdrew it. |
| `risk.AlertSuppressionProposed` | **Yes** | A holder of `RISK_INVESTIGATE` proposed, with a reason code (`SuppressionReason`: `FALSE_POSITIVE`, `ACTIVITY_EXPLAINED`, `RULE_DEFECT`, `DATA_DEFECT` - deliberately no `DUPLICATE`, a duplicate attaches) and a text, to suppress an open alert - to close it without a case (`P13-TSK-015`'s machine, `Alerts.proposeSuppression`; the door `POST /v1/operator/risk/alerts/{alertId}/suppression`, `P13-TSK-018`'s, the proposer a person - `CUSTOMER`- or `EMPLOYEE`-typed, `risk V014`): one live proposal per alert, awaiting a **different** holder of `RISK_CASE_APPROVE`; never on the proposer's own party's alert nor on one whose subject's party the platform cannot place (`risk.SelfDealingRefused`, fail closed). The text is the record's reason; the summary `kind=…, reasonCode=…` - never the subject, the evidence or the text. *(Added by `P13-TSK-015`, 2026-10-25: the rejection clears the alert's proposal, so the trail keeps it here.)* |
| `risk.AlertSuppressed` | **Yes** | A holder of `RISK_CASE_APPROVE` other than its proposer approved, with a reason, the proposal to suppress an open alert (`OPEN → SUPPRESSED`; `P13-TSK-015`'s machine, `Alerts.approveSuppression`; the door `…/suppression/approval`, `P13-TSK-018`'s, from a `MULTI_FACTOR` session; `INV-RSK-07`, `INV-AUD-04` - `risk_alert_suppression_four_eyes` beneath, and both actors persons by `CHECK` - `CUSTOMER` or `EMPLOYEE` since `risk V014`, the session typing every operator `CUSTOMER`); counted `finapp.risk.alert.suppressed{reason_code}`. Never the system's act: the sweep's deduplication of an alert into an open case is `ATTACHED` - its own fact, not a person's act, and not audited. The summary `kind=…, reasonCode=…` - never the subject. *(Emitted from `P13-TSK-015`, 2026-10-25: the machine raises it; the door landed with `P13-TSK-018`, 2026-10-25.)* |
| `risk.AlertSuppressionRejected` | **Yes** | A proposal to suppress an alert was rejected with a reason - or withdrawn by its proposer (`P13-TSK-015`'s machine, `Alerts.rejectSuppression`; the door `…/suppression/rejection` under `RISK_CASE_APPROVE`, `P13-TSK-018`'s): the proposal cleared from the alert, which stays `OPEN` and queued; this record and the proposal's keep both acts. The summary `kind=…, reasonCode=…, byProposer=true or false`. *(Added by `P13-TSK-015`, 2026-10-25.)* |
| `risk.CaseOpened` | No | A case was opened (`P13-TSK-016`; ADR-0110 decision 2): by T-eval on a `REVIEW` or `HOLD` decision in the judged command's transaction (the acting principal of that command the actor - `SecurityContext.require()`, as `bnpl.MerchantFinanced` refines the "system" actor), by an alert's folding with no open case of its kind (T-eval's or the profile leg's principal; the case leg's `SYSTEM` for the drained backlog; since `P13-TSK-025` the inbox delivery of `lending.LoanDefaulted` / `bnpl.BnplDefaulted` under `SYSTEM` - `RiskSignalIntake.asThePlatform` - a `COLLECTIONS` case of the defaulted agreement's party opened by its `OBLIGATION_DEFAULTED` alert), or by hand by a holder of `RISK_INVESTIGATE` (`POST /v1/operator/risk/cases`, keyed `risk.case:<type>:<id>`, a reason required at the door and recorded, never on the operator's own party). One open per (subject, kind) - `risk_case_one_open`; a disposed case is never re-opened, a new case names its predecessor. Its target the case (`risk_case`); the summary `kind=..., from=alert|assessment|hand, predecessor=PRESENT|NONE` - never the subject. *(Emitted from `P13-TSK-016`, 2026-10-25; a system's attach of a further alert or assessment is the case's history row, not audited - `-015`'s rule for the system's dedupe.)* |
| `risk.CaseTaken` | No | A holder of `RISK_INVESTIGATE` took a case (`POST /v1/operator/risk/cases/{caseId}/actions`, act `TAKE`, the body carrying the `revision`; `OPEN → IN_INVESTIGATION`) or took over a taken one from another investigator (`DESIGN-016` D12) - one person's act under the case row's lock and the revision compare-and-set; the investigator's own take converges with nothing written. The summary `kind=..., status=..., over=true|false`. *(Emitted from `P13-TSK-016`, 2026-10-25.)* |
| `risk.CaseNoted` | **Yes** | An investigator appended a note, or attached evidence by reference (signal, assessment and journal entry ids - resolved through audited reads, never copied, at most 100 of each), to an open case (acts `NOTE` and `ATTACH_EVIDENCE`); the text is the reason, bounded and screened by `InstrumentShapes`. The summary `kind=..., act=note` or `act=evidence, signals=n, assessments=n, journalEntries=n` - never the text or a reference. *(Emitted from `P13-TSK-016`, 2026-10-25.)* |
| `risk.CaseEscalated` | **Yes** | An investigator escalated a taken case with a reason (act `ESCALATE`; `IN_INVESTIGATION | ESCALATED → ESCALATED`), raising its level one step (`L1` to `L3`, the top refused `409 risk.CaseActNotAdmitted`) and routing it to the queue named (`SECOND_LINE`, `SENIOR_REVIEW`, `SPECIALIST`); announced `risk.CaseEscalated`. The summary `kind=..., level=..., queue=...`. *(Emitted from `P13-TSK-016`, 2026-10-25.)* |
| `risk.CaseDisposed` | No | A case reached its disposition (`→ DISPOSED`): a one-person close (`CLOSED_FALSE_POSITIVE`, `CLOSED_NO_ACTION`, from `OPEN` too - its proposer the actor) or a second person's approval of a `CONFIRMED_*`, `REFERRED` or prohibiting disposition (the approver the actor); announced `risk.CaseDisposed`. A case never re-opens; a new case references it. The summary `kind=..., disposition=..., prohibits=true|false`. *(Emitted from `P13-TSK-016`, 2026-10-25.)* |
| `risk.DispositionProposed` | **Yes** | A holder of `RISK_INVESTIGATE` proposed a case's disposition with a reason (`POST /v1/operator/risk/cases/{caseId}/dispositions`, the body carrying the revision; `CLOSED_FALSE_POSITIVE`, `CLOSED_NO_ACTION`, `CONFIRMED_FRAUD`, `CONFIRMED_SUSPICIOUS`, `REFERRED`, and `prohibit` - asked, never implied, of a confirming disposition of a party's case alone, `DESIGN-016` D3): the two `CLOSED_*` decided by this act; every `CONFIRMED_*`, every `REFERRED` and every prohibiting disposition needs the case taken (`422 risk.CaseNotTaken`) and awaits a DIFFERENT holder (from `-017`, any that issues an action joins them). One proposal stands at a time. The summary `kind=..., disposition=..., secondPerson=REQUIRED|NOT_REQUIRED, prohibits=...`. *(Emitted from `P13-TSK-016`, 2026-10-25.)* |
| `risk.DispositionApproved` | **Yes** | A holder of `RISK_CASE_APPROVE` other than its proposer approved a case's standing disposition from a `MULTI_FACTOR` session (`.../dispositions/{dispositionId}/approval`, the body carrying the revision; `INV-RSK-07`, `risk_case_disposition_four_eyes` beneath); the case `DISPOSED` in the same transaction; a prohibiting disposition's `PROHIBITED` profile version written after the case commits, in a transaction of its own (never R8 under R4 - `-013`'s order), its own record the version row and `risk.CustomerRiskRatingChanged`. The summary `kind=..., disposition=..., prohibits=...`. *(Emitted from `P13-TSK-016`, 2026-10-25.)* |
| `risk.DispositionRejected` | **Yes** | A standing disposition was rejected with a reason - or withdrawn by its proposer (`.../dispositions/{dispositionId}/rejection`, `RISK_CASE_APPROVE`); the case stays open, its slot cleared, the investigator actions and this record keeping both acts. The summary `kind=..., disposition=..., byProposer=true|false`. *(Emitted from `P13-TSK-016`, 2026-10-25.)* |
| `risk.ActionProposed` | **Yes** | A holder of `RISK_INVESTIGATE` proposed a downstream action on a case they hold taken, with a reason (`POST /v1/operator/risk/cases/{caseId}/risk-actions`, the body carrying the revision, `P13-TSK-017`; ADR-0112 decision 3): a kind the case admits (`ActionRules` - `SUSPEND_IDENTITY`, `REINSTATE_IDENTITY`, `REVOKE_SESSIONS` on a party's case's own party; `SUSPEND_MERCHANT` on a merchant-abuse case's own merchant; `RELEASE_HELD_WITHDRAWAL`, `DENY_HELD_WITHDRAWAL`, `CAPTURE_HELD_PAYMENT`, `VOID_HELD_PAYMENT` on a held movement the case holds), one live per (case, kind, target) by `risk_action_one_live`; never a reversal, never a credential reset; accounts' suspension and crossborder's block unavailable (no owning command). Its target the `risk_action` row (its id the case's `ACTION_PROPOSED` investigator action's - its key `risk.action:<id>`); the reason the analyst's; the summary names the case, the kind and the target's kind - never the target's subject or the reason. |
| `risk.ActionApproved` | **Yes** | A holder of `RISK_CASE_APPROVE` other than its proposer approved a case's standing action from a `MULTI_FACTOR` session (`…/risk-actions/{actionId}/approval`, `202`; `INV-RSK-07`, `INV-AUD-04` - `risk_action_four_eyes` beneath), the case's revision stepped with its `ACTION_APPROVED` act; the action `APPROVED` under a permit due at once and announced `risk.RiskActionRequested` - nothing dispatched in this transaction: the platform dispatches it after the commit (`risk.ActionDispatched`). The summary names the case and the kind. (An override's issued action is born `APPROVED` by the override's own approval - `risk.OverrideApproved` - and raises no second approval record.) |
| `risk.ActionRejected` | **Yes** | A standing action was rejected with a reason - or withdrawn by its proposer (`…/risk-actions/{actionId}/rejection`, `RISK_CASE_APPROVE`, `P13-TSK-017`): nothing dispatched; the live unique released, the action re-proposable. The summary names the case, the kind and whether the proposer withdrew it. |
| `risk.ActionDispatched` | No | The platform claimed an approved action - T-act's first attempt (`P13-TSK-017`; ADR-0112 decision 4): the `risk_action` row `FOR UPDATE SKIP LOCKED` on its due permit, moved `DISPATCHED` with the permit a lease ahead, committed before the owning domain's keyed command is called as the platform (`SecurityContext.enterSystem()` in `RiskActionDriver` - a person decided it, the platform executes it) under `risk.action:<id>` and the action's reason code; by the approval door after its commit or by `RiskActionSchedule`. The owning domain records its own audited act beside it with actor `SYSTEM`. A re-claim past the lease presents the same key and records nothing new here (the attempts are counted on the row). The summary names the kind, the source (`CASE`/`OVERRIDE`) and the target's kind. |
| `risk.ActionConcluded` | No | The answer the owning domain gave a dispatched risk action was recorded on its `risk_action` row, once, by a conditional `DISPATCHED → DONE \| FAILED` - first raised by `P13-TSK-012` for the action an approved override issues, now the same machine since `P13-TSK-017` folded it in (the override's action under its own id): `DONE`, `ALREADY` (the state already held - an identity already suspended, a movement already out of its hold by another hand, its expiry among them; concluded `DONE`) or `REFUSED` (the owner's own rule - a lapsed hold, a closed identity; concluded `FAILED`, never retried, re-proposable). The actor is the platform, which executes every action. The effect itself is the owning domain's act, audited by it. The summary names the kind, the source, the outcome and the attempts. |
| `risk.ReportDrafted` | **Yes** | A holder of `AML_REPORT_APPROVE` drafted a regulatory report from a disposed `CONFIRMED_SUSPICIOUS` case (`POST /v1/operator/risk/cases/{id}/reports`, keyed `risk.report:<actor>`, `P13-TSK-021`; ADR-0111): the jurisdiction, the reporting entity's reference, the subject and account references, the transactions referenced and the grounds (the case's reason codes and the narrative, `RESTRICTED-PII`) - one open draft per case. The summary names the case, the report and its jurisdiction - never the subject. *(Emitted from `P13-TSK-021`, 2026-10-25: from an `AML` case of a party only, under the case row's lock, its `REPORT_DRAFTED` investigator action in the same transaction (`DESIGN-021` D2); one live or accepted report per case - `DRAFT`, `APPROVED`, `FILED` or `ACKNOWLEDGED` - only a `REJECTED` filing freeing it (D3); the transactions, accounts and grounds drawn from the case (D5). Its target the report (`regulatory_report`); the reason the drafter's act reason, never the narrative; the summary `case=…, jurisdiction=…, formatVersion=…, accounts=n, transactions=n, unreferenced=n, grounds=n, attachments=n`.)* |
| `risk.ReportApproved` | **Yes** | A holder of `AML_REPORT_APPROVE` other than its drafter approved a regulatory report from a `MULTI_FACTOR` session (`…/approval`; `INV-RSK-07`). *(Emitted from `P13-TSK-021`, 2026-10-25: under the report row's lock, `DRAFT → APPROVED`, `regulatory_report_four_eyes` beneath; the drafter refused `403 risk.SelfApprovalRefused`, recorded `FAILED` (target the report, summary `case=…, jurisdiction=…, act=report:approve`). The summary `case=…, jurisdiction=…`.)* |
| `risk.ReportFiled` | **Yes** | A holder of `AML_REPORT_FILE` filed an approved regulatory report from a `MULTI_FACTOR` session (`…/filing`) through `RegulatoryFilingProvider` (simulated in Phase 13): `FILED` is committed before the call with the provider's reference pending, the artefact immutable from then; the acknowledgement, a rejection (a new draft) or an `UNKNOWN` reconciled by inquiry - never re-filed - are the machine's later facts, not audited acts. The summary names the report and the jurisdiction - never the subject (`INV-RSK-08`). *(Emitted from `P13-TSK-021`, 2026-10-25: `APPROVED → FILED` with the drive's permit due at once and `risk.RegulatoryReportFiled` (report id and jurisdiction) in the same transaction; the door then drives it as the platform (`RegulatoryFilingDriver`). The summary `case=…, jurisdiction=…, formatVersion=…`.)* |
| `risk.ReportAcknowledged` | No | The filing port's inquiry leg (the system actor, `RiskActionSchedule`'s report leg) recorded the authority's acknowledgement of a filed report (`FILED → ACKNOWLEDGED`, `P13-TSK-021`) - the provider's reference in the summary, never the subject. *(Emitted from `P13-TSK-021`, 2026-10-25, by the filing's drive as the platform - the filing door's own drive after its commit, or the report leg - conditional on `FILED` and the claimed attempt, once. The summary `jurisdiction=…, providerReference=…, attempts=n`.)* |
| `risk.ReportRejected` | No | The filing port's inquiry leg recorded the authority's rejection of a filed report (`FILED → REJECTED`, `P13-TSK-021`) - the rejection code in the summary; a new draft follows. *(Emitted from `P13-TSK-021`, 2026-10-25, as the acknowledgement. The summary `jurisdiction=…, rejection=<code>, attempts=n`; the case may then be reported by a new draft.)* |
| `risk.RegulatoryReportRead` | No | A holder of `AML_REPORT_APPROVE` read a regulatory report - its content, the narrative among it (`GET /v1/operator/risk/cases/{caseId}/reports/{reportId}`, `P13-TSK-021`, `DESIGN-021` D9: the second person reads what they approve; the narrative's one read door) - on a read-only snapshot after the self-dealing judgement (the reader's own party's report refused `risk.SelfDealingRefused`, recorded `FAILED`), committed in a transaction of its own **before** the answer, so the narrative is never served unrecorded. Its target the report; the summary `case=…, status=…, jurisdiction=…` - never the narrative or the subject. *(Added by `P13-TSK-021`, 2026-10-25 - the entry's three routes served no read; `risk.ReportRead` is `-024`'s operations reports'.)* |
| *planned* `risk.ListVersionRecorded` | **Yes** | A holder of `RISK_POLICY_ADMINISTER` recorded, with a reason, that a sanctions or PEP list version is in force (`POST /v1/operator/risk/screening-lists`, keyed `risk.list:<actor>`, `P13-TSK-022`; `UNIQUE (list, version)`). |
| *planned* `risk.ListVersionActivated` | **Yes** | A holder of `RISK_POLICY_ADMINISTER` activated a recorded list version (`…/{id}/activation`), enqueuing one rescreening permit per `ACTIVE` party for `RiskMonitoringSchedule`; kyc stays the screening owner (Refinement 3). The summary names the list, the version and the permit count. |
| `risk.ProfileRead` | No | A holder of `RISK_INVESTIGATE` read a party's customer risk profile history (`GET /v1/operator/risk/parties/{partyId}/profile`, `P13-TSK-007`, `-018`) - the current rating and every version with what set it, recorded in the read's own transaction after the self-dealing judgement, never the reader's own party (`risk.SelfDealingRefused`, fail closed). Its target the profile (`customer_risk_profile`, the party's id); the summary the number of versions served (`versions=n`) - never a rating, a score, a reason or the sealed inputs (`INV-RSK-08`). *(Built as planned, 2026-10-23.)* |
| *planned* `risk.ReportRead` | No | A holder of `RISK_INVESTIGATE` was served a risk operations report as of a date (`GET /v1/operator/reports/risk/{decisions,alerts,cases,velocity}?date=`, `P13-TSK-024`) - committed in a transaction of its own before the answer (`bnpl.ReportRead`'s shape), so no report is served unrecorded. *(Read "or a page of the alert or case queue (`-018`)" until `P13-TSK-018`, 2026-10-25, built the queues' pages as `risk.AlertRead` and `risk.CaseRead` - §11's own names for them.)* The summary carries counts and codes - never an amount, a party or a subject. |
| `risk.CaseRead` | No | A holder of `RISK_INVESTIGATE` read a case's file (`GET /v1/operator/risk/cases/{caseId}` - the case, its attached alerts, investigator actions, history and downstream actions, its evidence by reference only) or was served a page of the case queue (`GET /v1/operator/risk/cases?status=&kind=&after=&limit=`, oldest first by opening, keyset-paged), `P13-TSK-018` - on one read-only snapshot after the self-dealing judgement (a file of the reader's own party's subject, or one the platform cannot place, refused `risk.SelfDealingRefused`; such a case withheld from a page), committed in a transaction of its own **before** the answer, so nothing is served unrecorded. Its target the case (`risk_case`) or the queue (`risk_case_queue`, the status read or `OPEN_ANY`); the summary the file's kind, status, revision and counts (`alerts=n, actions=n, history=n, riskActions=n, evidence=n`), or the page's count, filter, cursor, the ids served, the withheld count and whether more remain - never a note, a subject or a reason. *(Emitted from `P13-TSK-018`, 2026-10-25.)* |
| `risk.AlertRead` | No | A holder of `RISK_INVESTIGATE` was served a page of the alert queue (`GET /v1/operator/risk/alerts?status=&kind=&after=&limit=`, `P13-TSK-018`): oldest first by raising on one read-only snapshot, every alert whose subject's accountable party is the reader's own or cannot be placed withheld, committed in a transaction of its own **before** the page is answered. Its target the queue (`risk_alert_queue`, the status read); the summary the count, the filter, the cursor, the ids served, the withheld count and whether more remain - never a subject, an evidence reference or a reason. *(Emitted from `P13-TSK-018`, 2026-10-25.)* |
| `risk.ReprocessingOrdered` | **Yes** | A holder of `RISK_POLICY_ADMINISTER` ordered the monitoring intake reprocessed from an earlier watermark (`POST /v1/operator/risk/monitoring/reprocessing`, `P13-TSK-019`): the watermark row locked `FOR UPDATE` (a running batch commits first), the point judged at or before its position (`422 risk.ReprocessingNotBehind` otherwise) and moved back to the sequence named - the millisecond of the journal's ids - with its permit due now. Its target the watermark row (`monitoring_watermark`, the source `ledger.journal` its id); the reason the officer's, required and screened for instrument shapes; the summary names the sequence restarted from and where the watermark stood - never the reason, an amount or a party (`INV-AUD-02`, `INV-RSK-08`). The re-run that follows creates nothing new - every monitored transaction is born once (`INV-RSK-09`). *(Built as planned, 2026-10-23.)* |
| `risk.SelfApprovalRefused` | No | A proposer tried to approve their own proposal - a policy activation, an override, an alert's suppression (`P13-TSK-015`: target `risk_alert`, summary `alert=…, kind=…, act=alert-suppression:approve`), a disposition, an action or a report - and was refused `403 risk.SelfApprovalRefused` (the `credit`/`lending`/`bnpl` precedent; `PHASE_13_PLAN.md` §9 corrected to `403` by the transition) before anything was written; recorded `FAILED` in a transaction of its own after the act rolled back (`INV-RSK-07`, `INV-AUD-04`). The summary names the proposal and the act. |
| `risk.SelfDealingRefused` | No | An operator tried to read or work a case, read an assessment or a profile, approve an override, read a page of the alert or case queue (`P13-TSK-018`: target `risk_alert_queue` / `risk_case_queue`, summary `act=alert-queue-read` / `case-queue-read, cause=UNPLACEABLE refused` - a page withholds own-party rows rather than refusing them) or a case's file (`act=case-read`, target the case), propose, approve or reject an alert's suppression (`P13-TSK-015`: target `risk_alert`, summary `act=alert-suppression-propose/-approve/-reject, cause=OWN_PARTY/UNPLACEABLE/SUBJECT_UNPLACEABLE refused` - the subject's party unplaceable fails closed too) or file a report on their own party's subject - or as an operator the platform cannot place in a party (fail closed; the Phase 12 → 13 gate's A7-6 applied forward) - and was refused `403 risk.SelfDealingRefused` with nothing written or read; recorded `FAILED` in a transaction of its own. The summary names the target and the act - never the subject. *(First raised by `P13-TSK-007`'s profile read, 2026-10-23: target `customer_risk_profile`, summary `act=profile-read, cause=OWN_PARTY|UNPLACEABLE refused`; the party is read through `RiskActingParty`, implemented by `app`'s `IdentityActingParty`.)* |

Thirty-two planned actions - `PHASE_13_PLAN.md` §11's list, each slash-separated name one action. **Payments' new outcomes
are payments' records, not risk's**: a withdrawal committed `HELD` or `FAILED(RISK_REFUSED)` (payments `V033`,
`P13-TSK-009`) and a payment intent refused by risk (`PaymentFailureReason.RISK_REFUSED`, `V034`, `-010`) are committed
states of payments' own machines, recorded by payments' existing outcome actions naming their states as today, and
`-009` and `-010` amend those rows in place; a held withdrawal's release or denial is a risk action dispatched through
payments' own audited door. *(As built by `P13-TSK-009`, 2026-10-24: the birth's act is named by its state -
`payments.WithdrawalDispatched` (permitted, unchanged), `payments.WithdrawalHeld` and `payments.WithdrawalRefused` (the
person's acts); the doors are `payments.WithdrawalReleased` and `payments.WithdrawalDenied` (the caller's act, a reason
code required), the expiry `payments.WithdrawalHoldExpired` (the platform's) - all five in the payments table above.)*
*(As built by `P13-TSK-010`, 2026-10-24: the checkout confirmation's refusal is `payments.PaymentRiskRefused` (the person's
act, recorded `FAILED` - the intent refused before any attempt, so no outcome action applies) and slice 2's deferral
`payments.PaymentCaptureDeferred` (the person's, beside `payments.PaymentConfirmed`); the held card payment's doors are
`payments.HeldPaymentCaptured` and `payments.HeldPaymentVoided` (the caller's act, a reason code required) and its
deadline `payments.HeldPaymentExpired` (the platform's); the capture and the void that follow are the existing commands'
own `payments.PaymentCaptureDispatched` / `payments.PaymentVoidDispatched` and outcome records - all five new actions in
the payments table above.)* **Not audited, by design**: the evaluation itself (T-eval writes the assessment - the
financial record of the judgement - not an audit record; the customer's keyed command is the audited act), the sweep's own
facts (monitored transactions, watermarks, alerts raised, profiles recomputed - rows and events, no person acts), and the
customer's read of a `HELD` withdrawal (payments' existing read, a status word only - C6).

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
