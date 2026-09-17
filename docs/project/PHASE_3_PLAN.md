# Phase 3 Plan — Accounts and Financial Ledger

**Status:** `IN_PROGRESS` — entry gate passed 2026-09-13; started the same day with `P3-TSK-001`
([`reviews/PHASE_2_TO_3_TRANSITION.md`](reviews/PHASE_2_TO_3_TRANSITION.md))
**Decisions:** ADR-0039 (posting concurrency), ADR-0040 (chart of accounts), ADR-0041 (balance
projection), ADR-0042 (account model) — all `Proposed`, accepted at the exit review
**Gate:** [`PHASE_GATES.md`](PHASE_GATES.md) §5 Phase 3 — *"the strictest gate in the
programme"*, plus the financial supplement F1–F8, which binds for the **first time**

---

## 1. Objective

**Establish the authoritative financial record.** Everything downstream inherits its
correctness: a transfer in Phase 4, a payment in Phase 5, a settlement in Phase 8 and a report
in Phase 14 are all, underneath, postings in this ledger.

By the end of Phase 3:

- a **chart of accounts** exists, with every ledger account declaring its type, normal balance
  and single currency, immutable once posted to;
- a **customer account product** can be opened, queried and closed, distinct from the ledger
  accounts recording its position;
- a **balanced journal entry** can be posted — atomically, idempotently, immutably — and an
  unbalanced one is impossible at both the domain and the database;
- a **balance is derived from postings** and reproducible from zero, with a projection that is
  fast, transactional and never the authority;
- **holds** reserve against available balance and release exactly;
- a **reversal** creates new compensating postings referencing the original, which stays
  byte-identical;
- a **trial balance** job asserts zero per currency, continuously, with alerting;
- a **statement** is derivable from postings for a period.

**Still no transfers between customers, no external payments, no FX conversion, no settlement.**
Money exists and moves within the ledger; nothing moves it on a customer's instruction yet.

## 2. Why this phase is different

Phases 0–2 built a platform that holds no money. This one does, and three things change:

1. **The financial supplement binds.** F1–F8 in `PHASE_GATES.md` §3 apply for the first time:
   trial balance zero per currency, balances reproducible from zero, idempotency at the
   financial boundary, reversal paths that never mutate history, duplicate-delivery safety,
   concurrency tests for every contended financial resource, no floating point anywhere on a
   monetary path, and reconciliation implications implemented or owned.
2. **`DB-PRIVILEGE` finally carries `INV-LED-03` and `INV-HIST-01`.** The mechanism has existed
   since `P0-TSK-022` — the application role holds no `UPDATE` and no `DELETE` — and has had
   nothing to protect until now. ADR-0033's rejection of an ORM was decided on exactly this
   ground; this is the phase where that decision starts paying.
3. **Mistakes become permanent.** `INV-HIST-01` forbids editing financial history, so a posting
   written wrongly is corrected by a compensating entry and the wrong one stays visible for
   ever. The cost of a design error here is not rework; it is a permanent record of the error.

## 3. Bounded contexts

| Context | Module | Owns | Does not own |
|---|---|---|---|
| Ledger (7) | `ledger` | Chart of accounts, ledger accounts, journal entries and lines, the balance projection, holds, the trial-balance job | Any customer-facing product concept |
| Accounts (5) + Wallet (6) | `accounts` | The customer account product and the wallet product: agreement, status, lifecycle, ownership | Postings; balances as authority |
| Party & Customer (1) | `party` | Who may hold an account — unchanged this phase | Anything financial |
| Audit (26) | `platform.audit` | The trail — unchanged | |

**`accounts` depends on `ledger`; `ledger` never depends on `accounts`** (ADR-0042). The ledger
knows an `owner_kind` and an opaque `owner_ref`; it does not know what a Customer Account is.
Enforced by module isolation tests in both directions, the `P2-TSK-003` shape.

**`ledger` is the single writer of postings** (`INV-LED-04`), enforced by a boundary test and by
grants: no other module holds `INSERT` on `journal_entry` or `journal_line`.

## 4. Account model

Four concepts, never collapsed (ADR-0042, `CLAUDE.md` §Domain Distinctions).

### Customer Account — the product

The agreement a customer holds. Status machine:

```
PENDING → ACTIVE → { SUSPENDED ⇄ ACTIVE } → CLOSED
```

`CLOSED` is terminal (`INV-LIFE-04`). Opening requires a customer whose verification says they
may transact — **the Phase 2 projection is the gate**: `party.customer.status = ACTIVE`, which
`INV-KYC-05` makes a faithful projection of the KYC decision. That is the first consumer of
Phase 2's product, and it is why Phase 2 came first.

A Customer Account **carries no balance**. It references its ledger accounts.

### Wallet — the stored-value product

A Customer Account whose product type is stored value — `ProductType.WALLET`, the phase's one
product, backed by the one customer-owned ledger purpose (`CUSTOMER_WALLET`). Phase 3 gives it
no funding rails — top-up and withdrawal need Phase 4's transfers or Phase 5's payments.

*(This paragraph also said "separate aggregate, own table" until `P3-TSK-012`, which recorded
the contradiction rather than silently picking a side: the backlog's own uniqueness rule — one
live account per customer per <strong>product type</strong> — puts the type on `CustomerAccount`,
no Phase 3 task builds a second aggregate, and ADR-0042's argument against a premature wallet
module — one aggregate, no independent lifecycle, no independent state — applies verbatim one
level down. The separate aggregate arrives with the independent lifecycle that is the ADR's own
recorded split trigger.)*

### Ledger Account — the accounting position

Flat, typed, single-currency (ADR-0040):

| Attribute | Rule |
|---|---|
| `account_type` | `ASSET` \| `LIABILITY` \| `EQUITY` \| `REVENUE` \| `EXPENSE` — closed, generates its `CHECK` |
| `normal_balance` | `DEBIT` \| `CREDIT`, stored, **immutable once posted to** (`INV-LED-06`) |
| `currency` | Explicit, one per account (`INV-MON-02`); multi-currency products hold *n* accounts |
| `owner_kind` | `CUSTOMER` \| `OPERATIONAL` \| `SUSPENSE` |
| `owner_ref` | The Customer Account, or null for platform-owned |
| `purpose` | Closed enum: `CUSTOMER_WALLET`, `SETTLEMENT_CLEARING`, `FEE_REVENUE`, `FX_POSITION`, `ROUNDING_RESIDUAL`, `SUSPENSE_UNMATCHED` |
| `gl_code` | Nullable — the Phase 14 seam |

### Operational Account — the platform's own

A ledger account with `owner_kind = OPERATIONAL` and no owner. Every customer credit is a
platform liability; both sides of a double entry are accounts, and a model with only customer
accounts cannot post anything.

### The four balances, and why they are four

| Balance | Definition | Source |
|---|---|---|
| **Settled / ledger balance** | Sum of posted lines, signed by normal balance | Postings — the authority |
| **Pending balance** | Value from entries not yet final, where a rail distinguishes them | Phase 5's; the seam is `journal_entry.status` |
| **Holds** | Sum of active holds | `ledger.hold` |
| **Available balance** | `settled − holds` | Derived (`INV-BAL-04`) |

Collapsing settled and available is how a platform authorises against funds already committed.
Phase 3 implements settled, holds and available; **pending is a seam only**, because nothing yet
creates a non-final entry.

## 5. Ledger model

### Journal entry and lines

An entry is the atomic accounting unit: **≥ 2 lines** (`INV-LED-02`), **debits = credits per
currency** (`INV-LED-01`), immutable once written (`INV-LED-03`).

```
journal_entry(id, posting_date, value_date, entry_type, reference, reason,
              actor_id, correlation_id, causation_id, idempotency_scope, created_at)
journal_line (id, entry_id, ledger_account_id, direction, amount_minor, currency, scale, seq)
```

**Three dates, never one** (`DOMAIN_MODEL.md` §Time): `created_at` is system time, `posting_date`
is the accounting date deciding the period, `value_date` is when value is available. Posting
date and value date are **inputs to the command**, never clock reads — a component deriving a
posting date by calling the clock has silently decided that execution time and accounting date
are the same thing.

**Every posting is attributable** (`INV-LED-05`): actor, correlation and the originating
economic event are `NOT NULL`.

### Debit and credit

Direction is an enum (`DEBIT` | `CREDIT`), never a signed amount. A signed amount makes
"unbalanced" a subtraction that happens to be non-zero; a direction makes it two sums that must
be equal, which is the property `INV-LED-01` actually states and the one a `CHECK` can carry.

`Money` is unchanged from Phase 0: integer minor units, explicit currency, stored scale, no
floating point anywhere (`INV-MON-01`, statically enforced).

### Reversal and adjustment

A **reversal** is a new entry whose lines are the original's with directions swapped,
referencing it (`INV-REV-01`); the original is byte-identical afterwards, asserted. Reversal is
bounded by the original, accounting for previous partial reversals (`INV-REV-02`).

An **adjustment** is a manual entry requiring an elevated permission and a reason code
(`INV-REV-04`). **Four-eyes is `INV-AUD-04`'s requirement and is recorded debt** (ADR-0010): the
second-approver column does not exist. Phase 3 builds the reason and the permission and records
the remainder with its owner — it does not pretend a threshold check is four-eyes.

### Suspense

A `SUSPENSE` account exists from the first migration and nothing posts to it in Phase 3. It is
the Phase 8 seam (`INV-REC-05`), and it exists now because value cannot be parked somewhere that
does not exist by the time there is value to park.

## 6. Financial invariants Phase 3 must preserve

Phase 3 needs **no new invariant group**, and that is worth stating: Phases 1 and 2 each had to
catalogue their properties because those existed only as gate prose. Phase 3's were catalogued
at project initiation, because Phase 3 is what the catalogue was written for.

| ID | What Phase 3 must make true | Enforcement |
|---|---|---|
| `INV-LED-01` | Every entry balances, per currency | `DOMAIN` + `DB-CONSTRAINT` |
| `INV-LED-02` | ≥ 2 lines per entry | `DOMAIN` + `DB-CONSTRAINT` |
| `INV-LED-03` | Posted entries and lines immutable | **`DB-PRIVILEGE`** |
| `INV-LED-04` | Ledger is the sole posting writer | `STATIC` + `DB-PRIVILEGE` |
| `INV-LED-05` | Every posting attributable | `DB-CONSTRAINT` (`NOT NULL`) |
| `INV-LED-06` | Type and normal balance declared, never changed after postings | `DOMAIN` + `DB-CONSTRAINT` |
| `INV-BAL-01` | Balances derived from postings | `DOMAIN` + `STATIC` |
| `INV-BAL-02` | Balance reproducible from zero | `DOMAIN` + continuous job |
| `INV-BAL-03` | Value neither created nor destroyed; residual posted, never absorbed | `DOMAIN` + `INV-LED-01` |
| `INV-BAL-04` | Available balance accounts for holds | `DOMAIN` + `DB-CONSTRAINT` |
| `INV-BAL-05` | No financial decision from an unbounded-staleness projection | `DOMAIN` (ADR-0041) |
| `INV-HIST-01` | Financial history never edited | **`DB-PRIVILEGE`** |
| `INV-REV-01` | Reversal is a new effect referencing the original | `DB-PRIVILEGE` + `DB-CONSTRAINT` |
| `INV-REV-02` | Reversal bounded by the original | `DOMAIN` + `DB-CONSTRAINT` |
| `INV-REV-04` | Adjustments carry reason and authorisation | `DOMAIN` + `DB-CONSTRAINT` |
| `INV-CON-01` | No lost updates on financial state | `DB-CONSTRAINT` + ADR-0039 |
| `INV-IDEM-01` | One financial effect per command per key | `DB-CONSTRAINT` |
| `INV-ACC-01` | Trial balance zero per currency | Continuous job + alerting |
| `INV-MON-01…06` | Money representation | `STATIC` + `DOMAIN` + `DB-CONSTRAINT` |
| `INV-AUD-01` | Every posting audited | `DOMAIN` + registry |
| `INV-EVT-01` | Fact and publication commit together | `DOMAIN` (outbox) |

**Each needs a mutation-register row as its enforcement lands** — and the Phase 2 → 3 transition
records the lesson that made that sentence precise: `MutationDemonstrationTest` derives its
demanded set from **the catalogue**, not from this table. `INV-HIST-02` belonged to Phase 2
while sitting outside both of its named groups and nobody noticed for four days. For Phase 3 the
in-scope set is **whatever `FINANCIAL_INVARIANTS.md` marks `Phase: 3`**, which includes rows
above that no `INV-LED`/`INV-BAL` group contains.

## 7. Multi-instance architecture

**Assume ≥ 10 instances.** ADR-0039 is the decision; this is how each hazard is met.

| Hazard | Mechanism |
|---|---|
| **Concurrent postings, same account** | Postings are **inserts**; no row is updated, so there is no lost update to have. `READ COMMITTED` |
| **Concurrent balance-affecting decisions** (holds, overdraft) | `SELECT … FOR UPDATE` on the **account row**, then derive from postings, then act — lock-then-look (ADR-0039) |
| **Duplicate posting commands** | `INV-IDEM-01`: unique (scope, key), claimed by insert, in the posting's transaction. The Phase 0 kernel, unchanged |
| **Duplicate inbound events** | Inbox dedupe key, committed with the effect (`INV-IDEM-04`) |
| **Retries / timeout-then-retry** | The idempotency record's `IN_PROGRESS` claim is reported as *unknown*, never as failure (`INV-LIFE-03`); a stale claim is reclaimed by the **server's** clock |
| **Crash mid-posting** | One transaction: entry, lines, projection, audit record and outbox row commit together or none do |
| **Crash between posting and publication** | Impossible to observe: the outbox row is in the same transaction (`INV-EVT-01`). Publication is at-least-once and consumers deduplicate |
| **Event ordering** | Per-aggregate ordering via the relay's advisory lock and the aggregate as partition key; consumers are order-independent or use an ordering key (`INV-EVT-04`) |
| **Stale reads** | The projection is transactional, so it is never behind; decisions do not read it anyway (ADR-0041) |
| **Uniqueness violations** | Expected, not exceptional: caught behind a savepoint so the caller's other writes survive |
| **Distributed scheduling** (trial balance, verification) | No leader. Either idempotent per period (`INV-IDEM-02`) or lease-protected — a new scheduler is a new `DISTRIBUTED_EXECUTION.md` §3 exemption, decided, not inherited |
| **Partial failure** | No cross-service transaction exists; the monolith's posting is one database transaction |

**Invariants that must be enforced by the database, not the application:**

1. `INV-LED-01` — entry-level balance `CHECK`, because the domain is not the only writer.
2. `INV-LED-02` — ≥ 2 lines, likewise.
3. `INV-LED-03` / `INV-HIST-01` — **`DB-PRIVILEGE`**: no `UPDATE`, no `DELETE`.
4. `INV-LED-05` — `NOT NULL` on actor, correlation, causation.
5. `INV-LED-06` — type and normal balance immutable once posted to (trigger, the `V003`
   idempotency-record precedent: a `CHECK` cannot see the previous row).
6. `INV-IDEM-01` — unique (scope, key).
7. `INV-MON-02` / `INV-MON-05` — currency `NOT NULL`, scale bounded.
8. `INV-BAL-04` — a hold may not exceed available balance, where representable.

**The multi-instance test convention is `P0-TST-009`**: one connection per simulated instance,
skew anchored on `SELECT now()`, never a shared connection and never a JVM clock deciding a
lease.

## 8. Data architecture

New schema `ledger`, owned by `finapp_migrator`, default-deny (`REVOKE ALL FROM PUBLIC`), the
`P2-TSK-003` shape. New schema `accounts` likewise.

| Table | Authoritative for | Grants |
|---|---|---|
| `ledger.ledger_account` | The chart | `SELECT, INSERT`; `UPDATE (status, …)` column-narrowed, never on type/normal balance/currency |
| `ledger.journal_entry` | Postings | **`SELECT, INSERT` only** |
| `ledger.journal_line` | Postings | **`SELECT, INSERT` only** |
| `ledger.account_balance` | The projection (derived) | `SELECT, INSERT, UPDATE` — it is *meant* to change; it is not history |
| `ledger.hold` | Reservations | `SELECT, INSERT`; `UPDATE (status, released_at)` |
| `accounts.customer_account` | The product | `SELECT, INSERT`; `UPDATE (status, status_changed_at)` |

Key constraints and indexes:

- `journal_entry`: `CHECK` that lines balance per currency (via trigger or a deferred constraint
  — a Phase 3 design task, since a `CHECK` cannot see sibling rows); `NOT NULL` attribution.
- `journal_line`: `CHECK (amount_minor > 0)` — direction carries the sign, so a negative amount
  is a modelling error rather than a credit.
- `ledger_account`: `UNIQUE (owner_ref, purpose, currency)` where owned; trigger freezing type,
  normal balance and currency once a line references the account.
- `hold`: partial index where active serving the availability read; `CHECK`s on amount positivity and status/release coherence; a trigger making `RELEASED` terminal for every writer. *(This row said "partial unique where active" until `P3-TSK-015`: uniqueness needs a subject, and the one-active-per-commanding-reference dimension arrives with Phase 4's flows — several active holds per account is the normal case, and a reference column nothing populates is the recorded anti-pattern. The index stays partial and deliberately not unique, with the provenance in `V008`.)*
- Every monetary column uses `MoneyColumns`' three-column shape (ADR-0003), unchanged.
- Every new column classified at its ceiling before the migration lands
  (`ColumnClassificationTest`) — balances and postings are **`RESTRICTED-FINANCIAL`**.

## 9. API architecture

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /v1/me/accounts` | session | Opens the caller's account product; requires `customer.status = ACTIVE` |
| `GET /v1/me/accounts` | session | The caller's products |
| `GET /v1/me/accounts/{id}/balance` | session, ownership | **Projection** — settled, holds, available; the response says what kind of number it is |
| `DELETE /v1/me/accounts/{id}` | session, ownership | Ends the agreement; zero-balance precondition; the history survives *(this row was missing until `P3-TSK-014` — the M3.4 milestone line says "open/query/close over HTTP" while this table omitted the close, the recurring plan-drift class)* |
| `GET /v1/me/accounts/{id}/statement` | session, ownership | Period statement derived from postings |
| `POST /v1/ledger/adjustments` | session, `LEDGER_ADJUST` | Manual adjusting entry; reason required; audited |
| `GET /v1/ledger/accounts/{id}` | session, `LEDGER_READ` | Operational view |
| `GET /v1/ledger/trial-balance` | session, `LEDGER_READ` | Per currency, as of |

**Posting is an internal API, not a public one.** No external caller may post an arbitrary
journal entry (`DELIVERY_PLAN.md` §Phase 3.7). The only public write is the adjustment, and it
is behind a new permission.

Contract discipline unchanged: byte-for-byte OpenAPI comparison, every endpoint declares an
authorization rule, every request body bounded, `Idempotency-Key` required on every
money-moving command (`@RequiresIdempotencyKey`, which has waited since `P0-TSK-017` for exactly
this).

## 10. Event architecture

| Event | Aggregate | Published when |
|---|---|---|
| `ledger.JournalEntryPosted` | The entry | In the posting transaction |
| `ledger.HoldPlaced` / `ledger.HoldReleased` | The hold | With the hold |
| `accounts.AccountOpened` / `AccountClosed` | The account | With the status change |

All via the outbox, in the same transaction as the fact (`INV-EVT-01`). Payloads carry
identifiers and enumerated names only — **never amounts** (`INV-AUD-02`): an event stream is
transport with different retention and access control, and a consumer needing the amount reads
the posting. `BalanceProjectionUpdated` is **deliberately not published**: the projection is
transactional with the posting, so an event announcing it would duplicate
`JournalEntryPosted` with no new fact.

## 11. Security and audit

- **Posting authority is privileged.** New permissions `LEDGER_POST` (internal, service-level)
  and `LEDGER_ADJUST` (manual entries), and a new role. The Phase 2 precedent applies: the
  role→permission mapping is mutation-testable because a second role now exists.
- **Adjustments require a reason code** (`INV-REV-04`), bounded and reconciled in three places
  the `P2-TSK-012` way.
- **Four-eyes is not built and the absence is recorded**, not disguised (`INV-AUD-04`, ADR-0010).
- **Account data is ownership-scoped** — the `/v1/me` shape, `SESSION_DERIVED`, no identifier a
  caller could point at someone else's account; every new store method classified in
  `OwnershipIsScopedTest` or the build fails.
- **Every posting is audited** with actor, correlation and outcome; new auditable actions
  catalogued before emission.
- Balance and posting columns classified `RESTRICTED-FINANCIAL`.

## 12. Reconciliation

Phase 3 is where reconciliation becomes *possible*, and it implements the internal half:

- **Trial balance zero per currency** (`INV-ACC-01`), a continuously-evaluated job with
  alerting — the system-level expression of `INV-LED-01`.
- **Projection verification**: recompute every balance from postings and compare to
  `account_balance` (`INV-BAL-02`), with a metric and an alert on any discrepancy.
- **Suspense accounts exist** for Phase 8 to park unmatched value without corrupting customer
  balances (`INV-REC-05`).

**External reconciliation is out of scope** — there is no external financial state to reconcile
against until a rail exists (Phase 5+). What Phase 3 owes is that every balance is explainable
from postings, which is `INV-BAL-02` and is the precondition for all of it.

## 13. Testing strategy

Per `ADR-0028`'s tiers. **This phase's tests are the deliverable as much as the code.**

- **Unit**: entry balancing across currencies and scales; direction/normal-balance sign
  derivation; account lifecycle transitions (every pair, derived from the machine); reversal
  arithmetic including partial reversals; allocation residual (`INV-BAL-03`, already proven in
  Phase 0 and re-exercised through postings).
- **Architecture**: `ledger`/`accounts` isolation both directions; **no module but `ledger`
  writes a posting**; no floating point on any monetary path; every new store method classified
  for ownership.
- **Database**: unbalanced entry rejected by the constraint (not only the domain);
  `UPDATE`/`DELETE` denied on every column of `journal_entry` and `journal_line` (the
  `P0-TST-007` column sweep, which found that a column-level grant is invisible in
  `table_privileges`); balance recomputation equals projection under sustained concurrent
  posting; hold cannot exceed available balance; released hold restores availability exactly;
  reversal leaves the original byte-identical; currency mismatch rejected; posting idempotent
  under ten concurrent identical keys.
- **Concurrency** (`P0-TST-009`): ten instances, own connection each — concurrent postings to
  one account; concurrent holds racing available balance; projection rebuild while postings
  continue; reclaim of a stale idempotency claim under clock skew.
- **Failure**: crash between posting commit and publication; injected failure at the last write
  of the posting; backend killed mid-transaction (`P1-TSK-012`'s deterministic-kill idiom);
  partial batch posting.
- **Mutation demonstrations**: every `Phase: 3` invariant in the catalogue gains a register row
  as its enforcement lands — and the set is read from the catalogue, per §6.

## 14. Failure scenarios

1. Crash between posting commit and event publication → outbox; publication is at-least-once.
2. Duplicate posting command, same key → one effect, original response replayed.
3. Duplicate posting command, **different** request, same key → refused (`INV-IDEM-03`).
4. Two concurrent postings racing the same balance → both succeed; balance is the sum; nothing
   is lost, because nothing was updated.
5. Two concurrent holds racing available balance → one wins, one refused; the account lock
   arbitrates.
6. Projection rebuild while postings continue → rebuild is consistent as of a snapshot;
   comparison tolerates in-flight entries by `last_entry_seq`, never by a time window.
7. Posting to a closed account → refused by the domain, and the account status is checked under
   the same lock.
8. Reversal of an already-fully-reversed entry → refused (`INV-REV-02`).
9. Unbalanced entry constructed in code → refused by the aggregate; and if it reaches SQL,
   by the constraint.
10. Currency mismatch within one entry → refused; `INV-LED-01` is *per currency*, so a
    multi-currency entry must balance in each.
11. Backend killed mid-posting → nothing written: no entry, no lines, no projection change, no
    audit record, no outbox row.
12. Trial balance job finds a non-zero sum → alerts and does **not** self-correct. A ledger that
    repairs itself has destroyed the evidence of what went wrong.

## 15. Observability

| Meter | Why |
|---|---|
| `finapp.ledger.posting` — counter by outcome | Posting throughput and failure rate |
| `finapp.ledger.posting.latency` — timer | The hot path of every later phase |
| `finapp.ledger.trial.balance` — gauge per currency | **Zero, or an incident.** `INV-ACC-01` as a series |
| `finapp.ledger.projection.drift` — gauge | Accounts where recomputation ≠ projection; zero at all times |
| `finapp.ledger.hold.active` — gauge | Hold utilisation |
| `finapp.accounts.account` — counter by outcome | Account lifecycle movement |

Registered **eagerly and unconditionally** (`P1-TSK-029`, and `P2-TSK-020`'s finding that a
plan-named meter behind a property condition is the same defect wearing a condition). A
dashboard row whose queries resolve against a live registry.

## 16. Milestones

| Milestone | Contents | Acceptance |
|---|---|---|
| **M3.1 — The chart exists** | `ledger` module and schema; `LedgerAccount` aggregate; chart seeded with operational accounts | A ledger account is created with a type, normal balance and currency, and its classification cannot be changed once posted to |
| **M3.2 — A posting is possible and cannot be wrong** | `JournalEntry`/`JournalLine`; the posting command; balance `CHECK`; immutability at `DB-PRIVILEGE`; idempotency | An unbalanced entry is impossible at the domain **and** the database; `UPDATE`/`DELETE` denied on every column; ten identical keys produce one effect |
| **M3.3 — A balance is explainable** | Derivation from postings; the transactional projection; the verification job | A balance recomputed from zero equals the projection, under sustained concurrent posting |
| **M3.4 — The product exists** | `accounts` module; Customer Account lifecycle; open/query/close over HTTP, gated on `customer.status = ACTIVE` | A verified customer opens an account, sees its balance, and closes it — end to end over HTTP |
| **M3.5 — Holds and availability** | `Hold` aggregate; place/release; available balance | A hold cannot exceed available balance under a ten-way race; release restores availability exactly |
| **M3.6 — Correction without mutation** | Reversal; adjustment with reason and permission | A reversal creates a new entry referencing the original, which is byte-identical afterwards |
| **M3.7 — Statements and the trial balance** | Statement derivation; the continuous trial-balance job with alerting | Trial balance is zero per currency, asserted by an automated job |
| **M3.8 — Observability and the gate** | The six meters; the phase review | A freshly started instance publishes every series; then the exit gate assessed with evidence |

## 17. What Phase 3 must NOT implement

| Must not | Why, and what is permitted instead |
|---|---|
| **Transfers between customers** | Phase 4. Postings exist; nothing moves money on a customer's instruction |
| **External payments, rails, PSPs** | Phase 5+ |
| **FX conversion** | Phase 9. The multi-currency *structure* is the seam: one currency per account, and an `FX_POSITION` purpose that nothing posts to |
| **Settlement, reconciliation matching, break records** | Phase 8. `SUSPENSE` accounts exist and nothing posts to them |
| **GL reporting, period close** | Phase 14. `gl_code` is nullable and unused |
| **Interest accrual, fees** | Phase 6/11. `FEE_REVENUE` exists as a purpose with no producer |
| **Four-eyes approval** | Recorded debt (`INV-AUD-04`, ADR-0010). The reason code and the permission are built; the second approver is not |
| **Pending balance semantics** | Phase 5 — nothing creates a non-final entry yet. `entry_type` is the seam |

## 18. Risks

| Risk | Mitigation |
|---|---|
| **Balance-as-truth drift** | The projection is transactional and never backs a decision (ADR-0041); continuous verification with alerting |
| **Silent history mutation** | `DB-PRIVILEGE` denies `UPDATE`/`DELETE`; ADR-0033 keeps an ORM off the classpath so nothing emits a statement nobody wrote |
| **Lost updates under concurrency** | ADR-0039; postings are inserts, decisions take the account lock; ten-instance tests with real connections |
| **Chart modelled too narrowly** | ADR-0040's attribute-based roll-up and the nullable `gl_code`; no hierarchy to re-parent |
| **The entry-balance constraint cannot be expressed as a `CHECK`** | A real design problem — a `CHECK` cannot see sibling rows. M3.2 must choose between a constraint trigger and a deferred constraint, and prove it against a direct `INSERT` that bypasses the domain |
| **Operational-account hot row** | ADR-0041 records it: partition by `purpose`, and any exception to the transactional projection is a separate decision |
