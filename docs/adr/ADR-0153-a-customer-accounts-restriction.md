# ADR-0153 — A customer account's restriction: suspension and reinstatement by four eyes, refusing new outflows at each command's own check

Status: Accepted (2026-11-20, at `P17-TSK-028`'s gate; written `Proposed` by its design and decided by the owner's rulings below)

Date: 2026-11-20

Phase: 17 (`P17-TSK-028`; finapp-admin-panel B6.5, gap G-33)

Context: Accounts (the customer agreement) · risk (its account actions) · transfers, payments, fx (their outflow checks)

Rests on: ADR-0039 (the transfer's serialization set), ADR-0042 (one ledger account per currency, owned by reference), ADR-0112
(risk's actions through the owning domain's door), ADR-0010 (audit), ADR-0031 (RBAC), the owner's C12 (roles a partition) and the
segregation-of-duties register (`P17-TSK-016`). **The owner's rulings of 2026-11-20, verbatim**: (1) "New outflows only
(Recommended)"; (2) "Analyst proposes, officer approves (Recommended)".

## Context

`CustomerAccountStatus` has modelled `ACTIVE ⇄ SUSPENDED` since Phase 3, and nothing produced `SUSPENDED`: accounts had no suspension
command (`P13-TSK-017`'s finding). So risk's `SUSPEND_ACCOUNT` and `REINSTATE_ACCOUNT` actions were unavailable, and an operator
could not freeze an agreement during an investigation.

A suspension is not a balance change. It is a question of **which money movements the agreement still admits**. The platform moves
a customer wallet's money on many paths. Some are commanded by the customer and post at once. Others **complete a movement an
external party has already made irreversible** - a card the provider captured, a withdrawal the scheme accepted, a refund the
provider paid, a credit the corridor accepted, a chargeback the network returned - and post after the fact, from a sweep that
retries until it succeeds.

The ledger already has a freeze: `POSTING_SUSPENDED` on a ledger account, which ledger `V007`'s trigger enforces against every
journal line. That is the strongest check, and the wrong one here: it refuses the completions too. A provider-captured top-up would
never be credited and a scheme-accepted withdrawal never debited, each sweep retrying forever: the money would have moved and the
books would not say so - the break `CLAUDE.md` rule 5 forbids.

## Decision

1. **The machine.** `ACTIVE → SUSPENDED` and `SUSPENDED → ACTIVE` on the agreement (`accounts.customer_account`), the edges the enum
   already declares (`RestrictionKind`). `CLOSED` stays reachable from `ACTIVE` only: a suspended agreement is reinstated before it
   is closed. Each move is the existing conditional `moveStatus` under the row's `FOR UPDATE` (`lockById`).

2. **What a suspended agreement refuses: new customer-initiated outflows, and nothing else** (ruling 1):
   - a transfer out of it (transfers' source: a committed `FAILED` `SOURCE_NOT_POSTABLE`, as for any non-active source);
   - a withdrawal, a wallet payment to a merchant, an FX conversion and a cross-border outbound credit - `409
     accounts.OutflowSuspended`, nothing written.

   **What it admits:** every credit; every completion of a movement already in flight (captures, scheme-accepted withdrawals,
   provider refunds, corridor credits and returns, won-dispute restorations) and the release of a hold; the customer's own
   repayments and the scheduled loan and BNPL collections, which reduce what the customer owes; system withdrawals that adopt a
   hold (loan payouts); operator corrections by their own doors. The agreement's ledger accounts stay `ACTIVE`;
   `POSTING_SUSPENDED` remains the ledger's own operational freeze, not a customer restriction.

3. **Checked at the posting's own check, under the agreement's lock.** Each refused path reads the agreement `FOR SHARE` inside the
   transaction that would hold or post, **before any ledger row is locked** (the agreement, then the ledger: the closer's own
   order, so no cycle), and requires it not `SUSPENDED` - transfers' `sourceOwnedBy` (`lockOwnedForShare`), and app's one
   `OutflowGate` behind payments' and fx's new `requireOutflowAdmitted` ports (a withdrawal's resolution, a wallet payment's
   confirmation, a conversion's and a cross-border acceptance's source). The suspension takes the same row `FOR UPDATE`. A
   movement racing a suspension therefore commits wholly before it (the suspension waits for its share lock) or is refused after
   it (its share lock waits, then reads `SUSPENDED`). Never half. The control is the command's, not a door's.

4. **Four eyes** (ruling 2). A proposal - `POST /v1/operator/accounts/{id}/suspension` or `/reinstatement` - by a holder of
   `ACCOUNT_RESTRICT`, with a reason code of the kind's vocabulary (`RestrictionReason`) and a reason; its approval or rejection -
   `POST /v1/operator/account-restrictions/{id}/approval` or `/rejection` - by a holder of `ACCOUNT_RESTRICT_APPROVE` who is not
   the proposer, with a reason. Every act on a `MULTI_FACTOR` session and keyed (`Idempotency-Key`; the platform's
   `IdempotentExecutor`, scoped per principal). The proposer is refused by identity at the domain and by `V003`'s `CHECK` for
   every writer (`INV-AUD-04`). `ACCOUNT_RESTRICT` sits on `RISK_ANALYST`, `ACCOUNT_RESTRICT_APPROVE` on `RISK_OFFICER` - two
   populations, so no one person holds both. At most one open proposal per agreement (the agreement's lock, and a partial unique
   index beneath it). The approval re-judges the kind against the agreement's status under its lock and moves it in the same
   transaction; when it no longer applies (a risk action or a close moved it), the approval is refused and the proposal stays
   open for a rejection. The row is the history: decided once, never deleted. A decision is never dated before its
   proposal: the two acts may run on two instances whose clocks disagree, so a decider running behind dates it at the
   proposal (`V003`'s `decided_after_proposed` would otherwise refuse a valid approval).

5. **Risk's actions use the same machine.** `SUSPEND_ACCOUNT` and `REINSTATE_ACCOUNT` become available: proposed on a party's case
   (`FRAUD`, `ATO`, `AML`) on **an agreement of the case's party**, judged at proposal through accounts' door. Risk's case approval
   is already four eyes (`RISK_CASE_APPROVE`, never the case's proposer), so risk's adapter (`AccountRiskActions`) moves the
   agreement directly under its lock, keyed `risk.action:<id>`, as `MerchantRiskActions` does: `DONE`, `ALREADY` or `REFUSED`.

6. **Holds and balances.** The restriction changes no balance, posts nothing and releases no hold. Open holds stand and complete or
   release by their own flows. Balances are the ledger's, unchanged (`INV-BAL-*`, `INV-LED-*`).

## Alternatives Considered

### Option A — the ledger's POSTING_SUSPENDED on every ledger account of the agreement (a total freeze)
Pros: enforced by the database on every writer; no change in any posting module.
Cons: strands money already moved by an external party, each sweep retrying forever; refuses credits a customer is owed; stops
repayments, so a suspended customer falls into delinquency and late fees.

### Option B — a direction-aware ledger status refusing debit lines
Pros: database-enforced.
Cons: a completion's capture is a debit too. The ledger cannot tell a debit that completes an in-flight withdrawal from a new outflow
without knowing the payments domain, so it either strands the completion or admits the new outflow.

### Chosen — refuse new outflows inside each command, under the agreement's share lock
Pros: completions and credits flow; the check is in the posting transaction, race-free by the row lock; reuses the `WalletAccounts`
precedent (an FX conversion's destination already required `ACTIVE` under `FOR SHARE`).
Cons: the check lives on each outflow path, so a new outflow path must add it.

## Consequences

Positive: a fraud or account-takeover suspension stops money leaving at once; nothing in flight is stranded.
Negative: four modules carry the outflow check; a new customer outflow path must call it (the follow-up below).
Operational impact: a suspended customer's incoming money still lands; reinstatement is four eyes too.
Security impact: two new permissions on two roles that are different populations; every act `MULTI_FACTOR`, keyed, audited.
Financial impact: none on balances; a racing movement is wholly before or wholly refused (`AccountRestrictionApiDatabaseTest`).

## Invariants / Constraints

`INV-LIFE-02` (the machine refuses invalid transitions); `INV-AUD-04` (proposer ≠ decider); `INV-IDEM-*` (keys); `INV-LED-*`,
`INV-BAL-*` unchanged.

## Follow-up

- A guard enumerating the customer outflow doors, so a new one cannot forget `requireOutflowAdmitted` (today the list is this ADR's
  decision 2 and the database test).
- The approver's queue (a §7 list of open restrictions) and a read of one restriction, for the console.
- A customer-facing statement of the restriction (the existing notification seam) and an integration event; a merchant's or a
  loan's restriction is separate.
