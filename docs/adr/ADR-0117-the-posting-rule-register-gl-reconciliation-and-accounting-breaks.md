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
   `ACCOUNTING_READ`, audited.)*
2. **Three checks per period**, each a close check: GL = ledger (mapping totality, no double mapping, sums equal per
   currency); rule conformance (namespace registered, shape conforming); source completeness (the existing proofs'
   verdicts through `SourceProofs`, never re-implemented).
3. **Accounting breaks** (owner decision C11): `accounting_break` born once per `(period, check, subject)`, types
   `MAPPING_GAP`, `DOUBLE_MAPPING`, `UNREGISTERED_POSTING`, `SHAPE_MISMATCH`, `UNBALANCED_ENTRY`, `SOURCE_DISCREPANCY`,
   `SOURCE_DELAYED`; `OPEN → ACKNOWLEDGED → RESOLVED`; acknowledgement four-eyes with a disclosure; resolution only by
   reference to a correcting entry posted through the owning domain's door. Never deleted, never self-corrected.
4. **Reconciliation's own breaks and suspense are read and disclosed** at close (`ReconciliationDisclosure`), not copied.

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
