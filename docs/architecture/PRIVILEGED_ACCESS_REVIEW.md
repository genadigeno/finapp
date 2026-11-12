# Privileged Access Review

*Written by `P15-TSK-021` (2026-11-08; `PHASE_15_PLAN.md` C13; `THREAT_MODEL.md` T3, T18, T31, T38). Who may act as an
operator on this platform, which role grants what, who may hold which roles together, how an operator's acts and
refused attempts are recorded, and the break-glass procedure. `PrivilegedAccessReviewTest` holds §3 and §4 against the
code. This document states controls; the tests named in each section build them. It claims no compliance with any
standard.*

---

## 1. Scope

Platform-native operator identities: a person who authenticates with a password and, for every four-eyes or
money-operating door, a TOTP second factor, and who holds one or more of the roles in §3. Workforce federation (an
external identity provider for staff) is deferred until a workforce identity provider exists (C13). Human access to
the cluster and the cloud account is `THREAT_MODEL.md` boundary B10's, not this document's: `P15-TSK-010` (in-cluster
RBAC) and `P15-TSK-016` (the cloud account's human baseline) own it.

## 2. Who is acting

Every audited act names an actor: an identity and the **population** it acted in (`ActorType`).

| Population | Who | How it is decided |
|---|---|---|
| `CUSTOMER` | A person acting on their own account | The session interceptor, for every request through a session that is not an operator act |
| `EMPLOYEE` | A person acting in an operational or administrative capacity: the operator population (the plan's "`OPERATOR`") | The session interceptor, for an **operator act** - the handler declares `@RequiresPermission` - by an identity holding at least one live role |
| `MERCHANT` | A merchant acting on its own API key | The merchant key interceptor |
| `SERVICE`, `SYSTEM` | An integration's credential; the platform itself | Their own doors; `SystemActorCallSitesAreEnumeratedTest` holds every `SYSTEM` site |

**The role decides, per request, from the server's record.** The interceptor reads the identity's live roles from
`identity.role_assignment` in the request's own authentication transaction, once, and that one read decides both the
population and the permission - so a permitted operator act is always recorded `EMPLOYEE`, and nothing the client sends
enters the decision. A **first** role - the identity held no live role - ends every session the grantee holds, in the
grant's own transaction, so a session a customer stepped up from the internet never serves a member of staff: the grantee
signs in again on the operator surface (`P15-TSK-043`; `FirstRoleEndsSessionsTest`, `IdentityAdministrationDatabaseTest`,
owed). A further role granted mid-session is honoured on the next request; a role revoked mid-session stops working on the
next request, as `INV-IDN-03` requires, and ends no session: the identity's sessions then serve what it is. A role holder acting on their own wallet, payment or loan
application is a customer in that act. Operator acts recorded before `P15-TSK-021` read `CUSTOMER` and stay as written
(history is append-only; the `*_by_type` columns are frozen by trigger). An idempotency scope keys a person once,
whatever population a request types them in (`Actor.keyedType`), so a retry crossing a role change or the release that
introduced the typing finds its record.

Held by: `OperatorActorTypingTest` (the interceptor), `DenyByDefaultDatabaseTest#theOperatorActorIsTyped` (the row in
`platform.audit_record`, owed to the exit database pass), `IdempotencyScopesKeyThePersonTest`.

## 3. The roles and what they permit

Every role is an operator role: a customer holds none. Each permission is held by exactly one role
(`RoleNameTest#theGrantsArePairwiseDisjoint`), so a permission names its population.

| Role | Permissions |
|---|---|
| `ADMINISTRATOR` | `IDENTITY_SUSPEND`, `ROLE_ASSIGN` |
| `KYC_REVIEWER` | `KYC_REVIEW`, `COUNTERPARTY_SCREENING_REVIEW` |
| `LEDGER_OPERATOR` | `LEDGER_POST`, `LEDGER_ADJUST`, `TRANSFER_REVERSE`, `PAYMENT_REFUND`, `MERCHANT_PAYOUT`, `PAYMENT_ROUTING_ADMINISTER`, `DISPUTE_ADMINISTER`, `FX_TRADE_REVERSE` |
| `MERCHANT_ADMINISTRATOR` | `MERCHANT_ONBOARD`, `MERCHANT_ADMINISTER`, `FEE_ADMINISTER`, `PAYOUT_DESTINATION_APPROVE` |
| `RECONCILIATION_OPERATOR` | `SETTLEMENT_INGEST`, `RECONCILIATION_INVESTIGATE`, `RECONCILIATION_RESOLVE`, `FX_INVESTIGATE` |
| `RECONCILIATION_CONTROLLER` | `RECONCILIATION_ADMINISTER` |
| `FX_CONTROLLER` | `FX_ADMINISTER`, `CROSSBORDER_ADMINISTER` |
| `CREDIT_POLICY_OFFICER` | `CREDIT_POLICY_ADMINISTER`, `CREDIT_INVESTIGATE` |
| `UNDERWRITER` | `CREDIT_UNDERWRITE` |
| `LENDING_OFFICER` | `LENDING_ADMINISTER`, `LENDING_INVESTIGATE`, `LOAN_WRITE_OFF_APPROVE` |
| `LENDING_TREASURY_OFFICER` | `LENDING_CAPITAL_ADMINISTER` |
| `LOAN_SERVICING_AGENT` | `LOAN_SERVICE`, `LOAN_SERVICE_APPROVE`, `LOAN_WRITE_OFF_PROPOSE` |
| `BNPL_OFFICER` | `BNPL_ADMINISTER`, `BNPL_INVESTIGATE`, `BNPL_WRITE_OFF_APPROVE` |
| `BNPL_SERVICING_AGENT` | `BNPL_SERVICE`, `BNPL_SERVICE_APPROVE`, `BNPL_DISPUTE_RESOLVE`, `BNPL_WRITE_OFF_PROPOSE` |
| `RISK_ANALYST` | `RISK_INVESTIGATE`, `RISK_CASE_APPROVE` |
| `RISK_OFFICER` | `RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE` |
| `AML_OFFICER` | `AML_REPORT_APPROVE`, `AML_REPORT_FILE` |
| `ACCOUNTANT` | `ACCOUNTING_READ`, `ACCOUNTING_MAPPING_PROPOSE`, `ACCOUNTING_CLOSE_PROPOSE`, `ACCOUNTING_ADJUST_PROPOSE`, `ACCOUNTING_REPORT_GENERATE` |
| `FINANCIAL_CONTROLLER` | `ACCOUNTING_MAPPING_APPROVE`, `ACCOUNTING_CLOSE_APPROVE`, `ACCOUNTING_ADJUST_APPROVE`, `REGULATORY_REPORT_APPROVE` |
| `REGULATORY_REPORTER` | `REGULATORY_REPORT_PREPARE`, `REGULATORY_REPORT_SUBMIT` |
| `EVENT_OPERATOR` | `EVENT_OPERATE` |

Which route requires which permission is `RoutePermissionRegisterTest`'s register. **Every route under `/v1/operator`
declares a permission** (`RoutePermissionRegisterTest#everyOperatorRouteIsPermissionGuarded`, T31): a route there
declaring only a session would serve any customer's session. The edge's generated allow-list keeps that prefix off the
public host by path as well as by permission (`P15-TSK-011`, `EdgeAllowListTest`), so the route would not be public either. Operator routes outside that prefix -
`/v1/identities/...`, `/v1/ledger/adjustments`, `/v1/kyc/cases/...`, a payment's refund and void, a transfer's
reversal - are permission-guarded and pinned by the register too.

## 4. Who may hold which roles together: the propose/approve partition

Where the vocabulary names an act's proposal (or preparation) and its approval as two permissions, no role holds both
(`RoleNameTest#noRoleProposesAndApprovesTheSameAct`). Six pairs, held by four pairs of roles:

| Act | Proposal | Held by | Approval | Held by |
|---|---|---|---|---|
| GL mapping | `ACCOUNTING_MAPPING_PROPOSE` | `ACCOUNTANT` | `ACCOUNTING_MAPPING_APPROVE` | `FINANCIAL_CONTROLLER` |
| Period close | `ACCOUNTING_CLOSE_PROPOSE` | `ACCOUNTANT` | `ACCOUNTING_CLOSE_APPROVE` | `FINANCIAL_CONTROLLER` |
| Prior-period adjustment | `ACCOUNTING_ADJUST_PROPOSE` | `ACCOUNTANT` | `ACCOUNTING_ADJUST_APPROVE` | `FINANCIAL_CONTROLLER` |
| Regulatory return | `REGULATORY_REPORT_PREPARE` | `REGULATORY_REPORTER` | `REGULATORY_REPORT_APPROVE` | `FINANCIAL_CONTROLLER` |
| Loan write-off | `LOAN_WRITE_OFF_PROPOSE` | `LOAN_SERVICING_AGENT` | `LOAN_WRITE_OFF_APPROVE` | `LENDING_OFFICER` |
| BNPL write-off | `BNPL_WRITE_OFF_PROPOSE` | `BNPL_SERVICING_AGENT` | `BNPL_WRITE_OFF_APPROVE` | `BNPL_OFFICER` |

**The holding rule.** No identity holds both roles of a row: not `ACCOUNTANT` with `FINANCIAL_CONTROLLER`, not
`REGULATORY_REPORTER` with `FINANCIAL_CONTROLLER`, not `LOAN_SERVICING_AGENT` with `LENDING_OFFICER`, not
`BNPL_SERVICING_AGENT` with `BNPL_OFFICER`. **Role assignment does not refuse such a holding today**: an
`ADMINISTRATOR` can grant it, and the grant is audited (`identity.RoleAssigned`, with a required reason). What still
holds when someone holds both is the act's own four-eyes: every approval refuses its proposer by identity, at the
domain and at the database (`INV-AUD-04`; `THREAT_MODEL.md` T18's rows), so one person still cannot propose and
approve the same act - holding both roles narrows the population, it does not open a door. Refusing the holding at
assignment is `THREAT_MODEL.md` §6 owner item 9. Four-eyes inside one role (`LOAN_SERVICE` / `LOAN_SERVICE_APPROVE`,
`BNPL_SERVICE` / `BNPL_SERVICE_APPROVE`, `RISK_INVESTIGATE` / `RISK_CASE_APPROVE`) is two holders of one desk, refused
by identity at the act.

**Granting.** Only `ROLE_ASSIGN` (the `ADMINISTRATOR`) grants or revokes a role; an administrator cannot grant a role
to their own identity (`IdentityAdministration`'s `SELF`); every grant and revocation is `identity.RoleAssigned`, with
the actor, the target, the role and a required reason.

## 5. The review

Who holds which role is read from the authoritative record, never from a spreadsheet: the live rows of
`identity.role_assignment` (granted, not revoked), per role, with the `identity.RoleAssigned` record of each grant -
who granted it, when and why. The review answers six questions *(read "four" until `P15-TSK-043`, 2026-11-12, though the
list held five since `P15-TSK-039`)*:

1. Does every holder still need the role? A role held without a current need is revoked, with a reason.
2. Does any identity hold both roles of a §4 row? It is resolved by revoking one, with a reason.
3. Does any identity hold `ADMINISTRATOR` beside another role? The administrator grants roles; holding one of the
   roles it grants is reviewed as a conflict and justified or revoked.
4. Was every grant since the last review made by a different person than its holder, with a reason that names a need?
5. Does any holder of a role that can redirect a merchant's money have no enrolled factor? `GET
   /v1/operator/access-review/factorless-holdings`, on a `MULTI_FACTOR` session, names them, from the live rows of both tables: each is resolved by
   the holder enrolling, or the role being revoked with a reason. The rule below refuses new ones; this finds the ones
   that stood before it (§6).
6. For every **first** role granted since the last review - an `identity.RoleAssigned` record saying `firstRole=true` -
   has the holder confirmed, to the reviewer and on the operator surface, that the identity's verified recovery channels
   (`identity.contact_channel`) and its enrolled factor (`identity.mfa_enrolment`, `ACTIVE`) are theirs? *(`P15-TSK-043`;
   `THREAT_MODEL.md` §6 item 39 (a2).)* The grant ended the sessions the identity held as a customer, but not what proves
   it: a channel or a factor added as a customer, from the internet, keeps proving the identity on the operator surface.
   Staff is decided as everywhere else, by any live role, so a re-grant after a revocation is a first role again and is
   asked again. A channel or factor the holder does not recognise is a compromise, not a correction: an `ADMINISTRATOR`
   suspends the identity with a reason naming it, which ends its sessions, and the runbook's compromise procedure follows.
   A holding written around the grant's door (a script, break-glass) has no such record, ended no session, and is
   asked this question as if it had one.

The review is a human procedure: the owner sets its cadence and its reviewer, and a leaver's roles are revoked on the
day they leave. Its first performance is `P15-TSK-032`'s, with the runbook's break-glass and compromise procedures.

## 6. Step-up and refused attempts

36 handlers declare `@RequiresAssurance(MULTI_FACTOR)`, every one on the method: Phase 13's risk doors, Phase 14's
accounting, ledger and write-off approvals, the dead-letter doors, the rulings below and §5's check. About thirty operations demand
the step-up themselves - a bank-account registration, a withdrawal, a wallet payment, the lending and BNPL doors - some
only when the caller has a factor, by one component (`ConditionalStepUp`). Not every four-eyes door steps up: a break's
resolution and the mapping activation are `P14-REM-001`'s and `P14-REM-002`'s. **Every refused privileged
attempt is recorded**: a missing permission as `identity.AuthorizationDenied`,
a session below the level as `identity.StepUpRefused` - each committed in a transaction of its own before the refusal is
answered, naming the actor, the identity and the operation's route pattern, never the session, the code or the factor
(`OperatorActorTypingTest`, `StepUpRefusalsTest`; `DenyByDefaultDatabaseTest#aRefusedStepUpIsRecorded`, owed). A
refusal whose record cannot be written is answered `500`, never without its record.

**Two questions this review rules**, both decided by the owner on 2026-11-08 and built by `P15-TSK-039`:

1. **May a role that can redirect a merchant's money be held without an enrolled factor?** *No.* Proposing a payout
   destination (`MERCHANT_ADMINISTER`), approving one (`PAYOUT_DESTINATION_APPROVE`) and paying out (`MERCHANT_PAYOUT`)
   move or redirect money that is not the operator's. **Built:** a grant of a role holding any of those three is refused
   `409 identity.FactorRequiredForRole` until the subject has a confirmed factor (`RoleGrantNeedsAFactorTest`), and the
   destination's proposal and approval declare `MULTI_FACTOR` unconditionally. Today's roles are `MERCHANT_ADMINISTRATOR`
   and `LEDGER_OPERATOR`; the rule reads the grants, so a permission moved to another role takes it along. A holding
   from before the rule is **named, not revoked**: §5 question 5. Until it is resolved, such a holder still cannot act
   on a destination (no factor, no `MULTI_FACTOR` session), and can still pay a merchant out on a password: the review
   closes that, not a migration.
2. **Do Phase 9's privileged acts require a multi-factor session?** *Yes*: the pricing and corridor policies' approvals,
   a kill switch's restart (the approval of its enable request, the act that restarts it) and an FX trade's reversal and
   its approval change what customers are charged or reverse a posted trade, the class every Phase 13 and 14 approval
   already steps up for. **Built:** the six doors declare it. A kill switch's *stop*, and a proposal to restart, stay
   one person's act on the session they hold: stopping is the fail-safe direction and a restart still needs a second
   person, stepped up. An `FX_CONTROLLER` with no enrolled factor, who could approve on a password until now, is refused
   `403 identity.AssuranceRequired` at these doors, and §5's check does not name that role: enrolment is self-service
   (`POST /v1/me/mfa`). `RuledDoorsStepUpTest` holds the eight doors and §5's check; the permission register pins them
   (`RoutePermissionRegisterTest#everyRuledRouteDeclaresMultiFactor`).

**Declared doors judge the level before the permission**, as every declared door does: at those nine, a caller on a
password session is told to step up whether or not they hold the permission. It denies a stolen password a way to learn
which roles the account holds. The conditional copies moved into `ConditionalStepUp` kept their call sites, so what a
refused caller learns there, and which refusal is recorded first, did not change.

**Removing a factor is the path around the rule, and it does not exist.** `MfaFactorStatus` has no state past `ACTIVE`,
nothing writes one, and no door or store method reaches a confirmed row (`RoleGrantNeedsAFactorTest#aConfirmedFactorCannotBeRemoved`,
which the task that builds removal must change). That task must refuse removal while a role needing a factor is held, or
revoke the role in the same transaction. Whatever it does, a destination act still needs a `MULTI_FACTOR` session.

**The wider class** is `door.ActRefused`: refused and failed Phase 7 and 8 acts and failed or not-found Phase 9 and 10
operator reads, the doors read from the controllers by `RefusedActsAreRecordedTest`. `AUDITABLE_ACTIONS.md` names what it
leaves out: a `401`, a key refused before an actor is proven, a filter's `413`, a customer's doors, and Phase 6's merchant
operator doors, a payout destination's among them.

## 7. Break-glass

There is no standing emergency identity in the application, and none is created. Break-glass is two procedures:

- **In the application.** An incident that needs a permission nobody on duty holds is met by an `ADMINISTRATOR`
  granting the role to a named person, with a reason naming the incident, and revoking it when the incident closes,
  with a reason. Both acts are `identity.RoleAssigned`; a grant left standing is found by the next review (§5, question
  1). Every act the person takes under the role is recorded `EMPLOYEE` and, at a four-eyes door, still needs a second
  person.
- **Beneath the application.** Human access to the cluster and the database is never standing: a time-bound, audited
  break-glass role in the cluster (`P15-TSK-010`), the database's bootstrap credential held only by the one-time
  bootstrap step (`P15-TSK-004`) in the secret store (`P15-TSK-018`).

`P15-TSK-032` writes and rehearses the runbook's break-glass and compromise procedures (`OPERATIONS_RUNBOOK.md` 11.10).

## 8. Report generation reads, and cannot write

Report generation reads as `finapp_reporting`, a database role holding `SELECT` on the 23 tables a report run reads
and nothing else - no write, no DDL, no restricted table (`ReportingRoleIsSelectOnlyTest`;
`ReportingRoleDatabaseTest`, owed). It connects through its own pool, counted in the connection budget, and never falls
back to the application's role (`ReportGenerationReadsAsTheReportingRoleTest`). It reaches the database the way the
application's pool does - the same URL and the same driver settings, `sslmode` among them - so the transport guard's
judgement covers it (`ReportingPoolTransportTest`). Startup refuses a reporting user naming the application's own
role, the migrator or the superuser, which would make the grants irrelevant (`ReportingPoolRoleTest`). **Beside the names,
what the role can do** (`P15-TSK-043`): every connection the pool makes asks the database first, and a role that is a member
of `finapp_app`, `pg_execute_server_program` or `pg_write_server_files` through any chain is refused whatever it is called; so
is one that, by itself, through `PUBLIC` or through any role it is a member of, outside the system schemas, holds `INSERT` or
`UPDATE` on any column of a table, view or foreign table (a column grant included, which the table's own privileges do not
show), `DELETE`, `TRUNCATE` or `TRIGGER` on one, `UPDATE` or `USAGE` on a sequence, or `CREATE` on a schema; and so is a
database with no ledger table to judge. Report generation then fails closed and the instance serves on
(`ReportingPoolCapabilityTest`; `ReportingPoolCapabilityDatabaseTest`, owed; `OPERATIONS_RUNBOOK.md` §11.37 for what on-call
sees). Functions a role may execute are not judged: the platform's `SECURITY DEFINER` functions are executable by
`finapp_app` alone. A role is a
cluster object: the local
initialisation script creates it, and in a deployed database the one-time bootstrap step does (`P15-TSK-004`).

## 9. What this review does not claim

It does not claim that the holding rule of §4 is enforced (owner item 9), that the holdings §6's check names have been
resolved or that the review of §5 has been performed, its question 6 included (a human procedure, first performed by
`P15-TSK-032`), that
operator routes are off the public edge (`P15-TSK-011`), or any conformity with a standard.
