# ADR-0117 — The posting rule register, GL ↔ ledger ↔ source reconciliation, and accounting breaks

Status: Proposed
Date: 2026-10-29
Phase: 14
Context: Accounting / General Ledger · Reconciliation · every posting module
Supersedes: nothing. Applies ADR-0069 (break taxonomy and lifecycle — the shape, not the tables), the
`*BooksHaveOnePosterTest` guards and the existing proofs. Rests on `PHASE_14_PLAN.md` §13.2, §13.3 and `INV-ACC-05`,
`-12`, `INV-REC-02`.

## Context

The posting rules exist in code at each poster and in five documents (`LEDGER_MODEL.md` §9–§11, ADR-0096 §2, ADR-0103,
the settlement and dispute lifecycles); nothing registers them in one place, nothing checks that an entry conforms to
its rule, and nothing ties the product proofs' verdicts to a period. Reconciliation's breaks are about external evidence
and park value in suspense; a GL-side discrepancy has neither.

## Decision

1. **The register is code** in `accounting` (pure data; purposes as strings): per namespace — source module, economic
   event, authoritative reference, rule id and version (with `effective_from`), line shape (purpose, direction, required
   or optional), dating class (`TODAY_CLOCK`, `TODAY_DATABASE`, `STORED_ROW`, `ECONOMIC_DATE`). `PostingRuleRegisterTest`
   in `app` fails the build on an unregistered key prefix or an unknown purpose.
   *(As built by `P14-TSK-002`, 2026-10-30: `PostingRuleRegister` in `accounting`, catalogue version 1, 58 rules read
   from every poster at master `0bfac06d` - 42 `POSTING` and 7 `REVERSAL` namespaces, and the adjustment rules keyed by
   the proposal's **origin** (`MANUAL`, `RECONCILIATION`), because every adjustment's scope is
   `ledger.adjust.approve:<proposal>`. Three refinements the code required, none a treatment: a namespace may carry
   several rules (one per economic event - `payment-capture:` a top-up or a merchant-bound capture), an entry conforming
   when it matches one in force; a line names any one of a set of purposes (a rail's clearing, a wallet or a payable); a
   line's side may be `EITHER` (a residual's sign, a statement's cash fold). The constructor refuses an incoherent
   catalogue; `inForce(type, namespace, postingDate)` resolves the version per rule id. `PostingRuleRegisterTest` also
   holds the posters both ways (every class constructing a ledger command is a registered poster, each poster constructs
   its rule's command), the manual adjustment to exactly the purposes open to free adjustment, and the back-dating
   posters by name: `ECONOMIC_DATE` is lending's accrual **and the manual adjustment**; nine more rules are
   `STORED_ROW` - input to `P14-TSK-006` and owner decision C3. Served at `GET /v1/operator/accounting/posting-rules`,
   `ACCOUNTING_READ`, audited.)* *(And by `P14-TSK-006`, 2026-10-30, C3 ratified: each rule names its **cutoff
   reaction** - `NOT_REACHED` (dated today), `REFUSED` (a back-dating system rule as it posted until C3),
   `FLOORED_AT_FIRST_OPEN` (its poster asks the ledger for the floor), `REFUSED_AT_APPROVAL` (a person's adjustment) -
   coherent with its dating by construction; the seven system rules versioned to 2, effective 2026-10-30; catalogue
   version 2, 65 rule versions; `PostingRuleRegisterTest` holds the floor to the source both ways.)*
2. **Three checks per period**, each a close check: GL = ledger (mapping totality, no double mapping, sums equal per
   currency); rule conformance (namespace registered, shape conforming); source completeness (the existing proofs'
   verdicts through `SourceProofs`, never re-implemented).
3. **Accounting breaks** (owner decision C11): `accounting_break` born once per `(period, check, subject)`, types
   `MAPPING_GAP`, `DOUBLE_MAPPING`, `UNREGISTERED_POSTING`, `SHAPE_MISMATCH`, `UNBALANCED_ENTRY`, `SOURCE_DISCREPANCY`,
   `SOURCE_DELAYED`; `OPEN → ACKNOWLEDGED → RESOLVED`; acknowledgement four-eyes with a disclosure; resolution only by
   reference to a correcting entry posted through the owning domain's door. Never deleted, never self-corrected.
   *(As built by `P14-TSK-010`, 2026-10-31, accounting `V006`: decisions 2 and 3 as one callable the close run invokes
   per check, `AccountingChecks.run` → `CheckOutcome` (`PASSED` only when the judge read everything and found nothing;
   any discrepancy, unavailable proof, unreadable judge or month without a mapping version `FAILED` with its reason;
   every outcome and break naming the register version it judged by - `posting-rules:v<n>`, `gl-mapping:v<n>`,
   `source-proofs:v1`). The judges read only, each on a verified, statement-bounded `REPEATABLE READ READ ONLY`
   snapshot - source completeness on one per source; every new finding is raised in its own transaction with its
   case-file row, audit record and `AccountingBreakRaised`, born once by `accounting_break_born_once UNIQUE
   (period_start, check_code, subject_ref)` - the period held as its month. `SourceProofs` is given its method (one
   reading per source, the nine proofs over seven sources) and `app`'s `ProductSourceProofs` its implementation, every
   verdict the proof's own. Acknowledgement is a disclosed proposal and a different person's approval (`CHECK`
   `accounting_break_acknowledgement_four_eyes`); resolution names an existing correcting entry other than the subject;
   `BreaksNeverPostTest` holds the machinery to no ledger writer, cutoff or prior-period door. `PHASE_14_PLAN.md` §13.3's
   note records the subject granularity and the deviations.)*
4. **Reconciliation's own breaks and suspense are read and disclosed** at close (`ReconciliationDisclosure`), not copied.
   *(As built by `P14-TSK-011`, 2026-11-02: as of the cutoff's instant, from reconciliation's frozen stamps and the
   append-only releases - `app`'s `ProductReconciliationDisclosure`, the `RECONCILIATION_DISCLOSURE` check `DISCLOSED` when
   anything stands; `PHASE_14_PLAN.md` §13.5's note.)*

## Alternatives Considered

### New break types in reconciliation's table
Pros: one break queue. Cons: reconciliation's machinery presumes evidence and suspense; mixing internal accounting
discrepancies in changes its invariants. Rejected (C11 B).

### The register as versioned data activated by four eyes
Pros: operator-changeable. Cons: rules change only with code; data would describe code it cannot change. Rejected.

## Consequences

Positive: every entry traceable to a registered rule and source. Negative: a new poster must register its namespace in the
same change. Operational impact: breaks alerted by age. Security impact: acknowledgement four-eyes. Financial impact:
detective only; nothing posts.

## Invariants / Constraints

`INV-ACC-05`, `-12`; `INV-REC-02`; `INV-LED-05`.

## Follow-up

Line-shape conformance for amounts (e.g. fee ≤ gross) as rule predicates, if the battery shows value.

## Open questions

- **A recurrence after resolution** (`P14-TSK-010`'s independent review, I-1; the owner's, its consumer `P14-TSK-011`):
  the key `(period, check, subject)` holds one break for good, so a discrepancy still present after its break was
  resolved - or a new discrepancy under an aggregate subject (`namespace:`, `key:`, `currency:`, a proof's subject) -
  raises nothing new. As built it is never hidden: `CheckOutcome` counts known findings by their break's status and
  reports a `RESOLVED` one as `RECURRED`, so the close can tell "all disclosed" from "a resolved break's discrepancy is
  still present". Whether it should instead raise a new break - re-raise on `RESOLVED`, or a generation in the key - is
  open; the key and the re-raise rule were deliberately left unchanged. *(`P14-TSK-011`, 2026-11-02, its consumer: the
  default until the owner rules - a `RECURRED` outcome is `FAILED` and never `DISCLOSED`, whatever else it holds. *Corrected by its
  independent review, 2026-11-02, I-2: this text read "blocked until the discrepancy is really gone. Either answer fits", which does not hold for an
  immutable subject.* A journal entry never changes and `RESOLVED` is final, so a `RESOLVED` `shape:<entry>`, `unbalanced:<entry>` or `namespace:` break is
  `RECURRED` on every re-run: that discrepancy is never gone, and the close of its period - periods closing in order, of every later one - is blocked for
  good. So the answer is a precondition of `P14-TSK-012`, not a refinement: a re-raise or a generation gives the finding a new break to acknowledge; the
  default gives it no way out.)*
- **What an acknowledgement holds** (`P14-TSK-011`'s review, I-1; open, the same owner decision as the recurrence above): four eyes acknowledge what a
  break's evidence says, and the key holds only a subject, so `namespace:<TYPE> <ns>` and `<PROOF>:<subject>` could change after the acknowledgement and
  still read disclosed, across an unlock too. **The safe default as built**: `held` answers the break's evidence, and a finding differing from it is
  `FAILED ACKNOWLEDGED_CHANGED`, never `DISCLOSED`, its subject on the attempt; nothing is re-raised and the break stays what it was. Evidence of unchanged
  facts is stable - lines by sequence, rules in the register's order, first entries in keyset order and capped, sorted currencies and keys, no clock.
  **Its limits**: evidence holds no amounts, so a change of amount alone is not seen. And a source proof reading the live book (`POSITION`'s open
  counts, a `CASH_AT_BANK` `latestSequence`, a loan's status) can move on every re-run with no door to re-acknowledge, so this default may wedge it as
  `RECURRED` wedges an immutable subject. Which granularity is right - the exact evidence, a stable part of it, a re-acknowledgement - is the owner's.
- **Resolution's residual** (I-2): a correction must exist, not be the subject or listed in the break's evidence, and
  not predate what it corrects; that it corrects *this* discrepancy, that it is not reused across breaks, and that one
  person decides it remain the controller's audited judgement.
- **A mirror reversal** (M-7): a reversal of a nonconforming entry is judged against its own REVERSAL rule at the
  reversal's date, so it raises its own break - whether a reversal should instead be judged as its original's mirror is
  open.

*(The independent review of `P14-TSK-010`, 2026-11-01, said FIX FIRST; fixed on the branch: I-1 (`RECURRED`, the known
findings split by status), I-2 (the correcting entry's plausibility, a refusal recorded `FAILED`), I-3 (a database case
running the wired `ProductSourceProofs` through `SOURCE_COMPLETENESS`; by reading, no source locks, writes or needs a
read-write transaction), M-2 (a judge's exception logged by class), M-3 (the month's boundary days in the database
suite), M-4 (the break list a keyset page), M-5 (`accounting.BreakRead`), M-6 (a repeated proposal with another
disclosure stale), M-12 (identifier-like namespace segments folded). Routed: M-1 (no bound on a whole run below the close
run's lease; `run` called outside any transaction) and M-10 (reconciliation's `suspenseUnowned` as a
`RECONCILIATION_DISCLOSURE` input) to `P14-TSK-011`; M-7 to the owner; M-8 (offsetting differences within one currency
cancel in GL = ledger's per-currency sums and in the per-currency balance check) a known limitation; M-9 documented in
`BreaksNeverPostTest`; M-11 at the integration merge.)*
