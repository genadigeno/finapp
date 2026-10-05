# ADR-0078 — Counterparty-keyed clearing positions: the split trigger fires, on accounts with no history

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: Ledger · Settlement · Reconciliation · App
Supersedes: nothing. Fires the split trigger ADR-0062's Consequences recorded ("two schemes in
one currency would need owner-keyed clearing accounts") — for the **new** clearing purposes
only. Annotates ADR-0064 §5 (the source register, now composed per counterparty), ADR-0065 §4
(the per-flow settlement shapes, now also on counterparty positions) and ADR-0067 §3 (keyed on
the declared clearing purpose — the declaration now also names a counterparty). Restates
`INV-SET-05` and `INV-RAIL-04` per counterparty. The existing operational clearings and their
history are untouched.

## Context

Phase 8 reconciles three clearing positions, each an OPERATIONAL account per currency:
`SETTLEMENT_CLEARING` (the card PSP), `INSTANT_CLEARING` (the scheme) and `PAYOUT_CLEARING`
(the payout provider). One position per purpose worked because each purpose had exactly one
counterparty. Phase 9 breaks that assumption twice:

1. **The FX provider and the corridor provider are counterparties with money owed in both
   directions** — `FX_PROVIDER_CLEARING` (what the provider owes us per currency, ASSET/DEBIT)
   and `CORRIDOR_CLEARING` (what we owe the provider for accepted, unsettled credits,
   LIABILITY/CREDIT).
2. **M9.8 adds a second provider of each kind** (D19), and two counterparties on one purpose
   must never net (`INV-RAIL-04`): a receivable from `fx-sim-a` does not pay a payable to
   `fx-sim-b`, and a position proof over their sum would prove nothing.

The chart of accounts is keyed `(owner_ref, purpose, currency)` for owned accounts and
`(purpose, currency)` for operational ones (ADR-0040, ADR-0042). The forces on the mechanism:

- `owner_ref` is a `uuid`; a counterparty *code* cannot live in it (the accounting candidate
  design's defect).
- Runtime-minted accounts would break the seeded-id lock-order rule the Phase 7 → 8 transition
  repaired dispute postings around (`everySeededIdSortsBeforeEveryRuntimeId`): hot projection
  rows are locked in account-id order, and seeded ids must sort first.
- Phase 8's proofs (`INV-REC-06`'s position identity, completeness, `PositionProof`'s `PROVEN`
  list) and `INV-SET-05`'s one-source rule are stated per position. They must become per
  (purpose, counterparty) without changing any existing position's verdict.
- Re-keying the three existing clearings would move Phase 5–8 history for no behavioural gain.

## Decision

1. **The split trigger fires for the new clearings only** (D18). `FX_PROVIDER_CLEARING` and
   `CORRIDOR_CLEARING` are counterparty-owned from birth. The existing operational clearings
   (`SETTLEMENT_CLEARING`, `INSTANT_CLEARING`, `PAYOUT_CLEARING`) keep their accounts, their
   single counterparties and their history, untouched. No history moves, because the split
   lands on accounts that have none.
2. **A registry owns counterparty identity.** `ledger.counterparty (id uuid PK, code text
   UNIQUE CHECK '^[a-z][a-z0-9-]{0,31}$', kind CHECK IN ('FX_PROVIDER','CORRIDOR_PROVIDER'),
   created_at)` is seeded by migration, with SELECT and INSERT grants only — rows are never
   updated or deleted. **Counterparty** enters the glossary as a declared external party
   owning a clearing position, distinct from a customer or a merchant.
3. **`OwnerKind.COUNTERPARTY` is added, and the four generated constraints are restated**
   (ledger `V021`): `owner_kind_is_known`; `purpose_is_known`; `owner_kind_matches_purpose`,
   with `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` ⇒ `COUNTERPARTY`; and
   `owner_ref_matches_kind`, with `COUNTERPARTY` ⇒ `owner_ref` names a registry row, enforced
   by a trigger for every writer. The owned-account unique `(owner_ref, purpose, currency)`
   already has the right shape and is unchanged.
4. **Every counterparty clearing account is seeded by the migration that admits its
   counterparty** — one per declared currency, with hand-minted UUIDv7 ids stamped
   `2026-09-27T12:00:00Z`, below the ceiling, so `everySeededIdSortsBeforeEveryRuntimeId`
   holds and the hot-row lock order (account-id order, seeded ids first) is undisturbed.
   **Nothing is minted at runtime.** Ledger `V022` admits `fx-sim-a` (five currencies,
   `P9-TSK-011`), `V024` admits `corridor-sim-a` (three, `P9-TSK-014`), `V025` admits the
   `-b` pair (`P9-TSK-026`) — a second provider is a declaration, a seed migration and an
   adapter.
   - A `CounterpartyChartGuard` refuses startup when a declared counterparty, or a
     counterparty × declared currency, lacks its registry row or account (the
     `SettlementBeans` refusal precedent).
   - `OperationalChartMigrationTest` gains the counterparty part: each (counterparty, purpose,
     currency) seeded once, ids below the ceiling, types pinned.
5. **Resolution is by counterparty, explicitly.** `ChartOfAccounts.resolve(uow, purpose,
   counterpartyCode, currency)` serves counterparty purposes; the existing
   `resolve(purpose, currency)` **refuses** them. No caller can post to "the"
   `FX_PROVIDER_CLEARING` — there is no such account, only `fx-sim-a`'s.
6. **The settlement source register is keyed per (purpose, counterparty).**
   `SettlementSourceDescriptor` gains `Optional<String> settledCounterparty`, present exactly
   when the settled purpose is counterparty-owned, and with it the counterparty's **settled
   currencies** (the declaration's: all five for `fx-sim-a`, `{USD, JPY, BHD}` for
   `corridor-sim-a`). `SettlementSources.of` refuses two sources on one (purpose,
   counterparty) — `INV-SET-05` restated. Existing descriptors carry no counterparty and
   behave as before (ADR-0064 §5 annotated: each position, and now each counterparty, is read
   from its declaration — `FxProviderDeclaration.clearingPurpose()` and the corridor rail's
   `CorridorDeclaration` — never hand-named; `CounterpartyClearingIsNamedByDeclarationsTest`
   admits only the declarations and the settlement composition to name the counterparty
   purposes). Settlement recognitions resolve their accounts by the source's counterparty.
7. **A batch in a currency its counterparty does not settle is refused loudly.** A parsed
   batch whose currency has no clearing account for the source's counterparty is rejected and
   retained with the new appended `RejectionCode.CURRENCY_NOT_SETTLED` (settlement `V015`):
   never accepted, never posted (`INV-SET-07`'s screening discipline extended by one truthful
   refusal).
8. **The proofs are derived from the register and keyed per (purpose, counterparty,
   currency).** `PositionProof`'s hard-coded `PROVEN` list is **derived from the composed
   register**, so a counterparty admitted by migration is proven by construction, and
   completeness walks **every account** of a reconciled purpose, not one per purpose. The
   position identity (`INV-REC-06`) holds per counterparty position; existing positions'
   verdicts are byte-unchanged.
9. **`INV-RAIL-04` restated: a clearing position is (purpose, counterparty) for
   counterparty-owned purposes — and still never nets two counterparties.** Each counterparty
   settles on its own position; a cross-counterparty discharge has no account to land on,
   which is the point.

## Alternatives Considered

### Single operational clearings (one `FX_PROVIDER_CLEARING` per currency)
Pros: no new owner kind, no registry, Phase 8's shapes unchanged.
Cons: the moment a second provider exists (M9.8, D19), its receivables and the first's
payables net inside one balance; `INV-SET-05`'s "one source per settling position" either
blocks the second provider or loses its meaning; and the position proof can no longer say *who*
owes *what*. The distributed candidate design chose this and was judged thin on exactly brief
20 (multiplicity). Keying by counterparty from birth costs one migration now and makes M9.8 a
seed plus an adapter.

### The counterparty code inside `owner_ref`
Pros: no registry table.
Cons: `owner_ref` is a `uuid`; a code does not fit it, and widening the column to text for one
owner kind would weaken every other kind's reference. Rejected as the accounting candidate
design's defect — the registry gives the code a uuid identity, a format `CHECK` and a trigger-
enforced reference.

### Runtime-minted counterparty accounts (first posting creates the account)
Pros: no seed migrations.
Cons: it breaks the seeded-id ordering that the hot-row lock order rests on
(`everySeededIdSortsBeforeEveryRuntimeId`), reintroducing the deadlock class the Phase 7 → 8
transition repaired; and an account born mid-flight has no migration provenance. Rejected as
the accounting candidate design's second defect. Seeding by the admitting migration keeps the
chart a reviewed, auditable fact.

### Re-key the three existing clearings per counterparty too
Pros: one uniform shape.
Cons: moves Phase 5–8 history and re-proves every existing proof for zero behavioural change —
each existing purpose has exactly one counterparty today. The split trigger was recorded for
*new* multiplicity; firing it retroactively is churn. The existing positions keep their shape
until a second counterparty of theirs exists.

## Consequences

Positive:
- Two counterparties can never net, by construction: there is no shared account for them to
  meet in (`INV-RAIL-04`).
- A second provider of either kind is a declaration, a seed migration and an adapter — proven
  in M9.8 — with its position, source, proofs and completeness arriving derived, not
  hand-listed.
- The chart stays fully seeded, reviewed and ordered; the lock-order rule and every existing
  proof verdict survive byte-identical.
- Who owes what, per counterparty, per currency, is a ledger fact an operator can read and a
  proof can assert.

Negative:
- A new owner kind touches the ledger's most load-bearing generated constraints; restating all
  four in `V021` (rather than patching one) keeps them reviewable but makes the migration
  wide. The re-run Phase 8 storm, proof-group and settlement suites guard it.
- The two-argument `resolve` now has a refusing branch, and every settlement recognition path
  must carry a counterparty — a small, permanent tax on call sites, bought back by the
  impossibility of posting to an ambiguous position.
- The registry is append-only by grants; renaming a counterparty is deliberately impossible
  (a new code is a new counterparty with new accounts), which is correct for books and
  occasionally inconvenient for operators.

Operational impact: startup refuses on an incomplete chart (`CounterpartyChartGuard`), so a
missing seed is caught at deploy, not at first posting. The reconciliation position, suspense,
cash and completeness proofs and their gauges now report per (purpose, counterparty, currency);
no new meter is needed (the existing source meters cover the new sources). A file in an
unsettled currency surfaces as a retained `CURRENCY_NOT_SETTLED` rejection, counted on the
existing rejection meters.
Security impact: `ledger.counterparty` carries SELECT and INSERT grants only — no writer can
rename or re-point a counterparty under an existing account. The `owner_ref` trigger holds for
every writer, raw SQL included. No new route, role or permission.
Financial impact: none moves. The decision determines where Phase 9's cover entries,
outbound-credit completions, returns and hop-1/hop-2 recognitions land (ADR-0076, ADR-0077,
ADR-0079, ADR-0082), and guarantees each counterparty's balance is explainable alone —
CLAUDE.md rule 11 held per counterparty.

## Invariants / Constraints

`INV-SET-05` (restated: one source per (purpose, counterparty), refused at composition and
proven both ways at build time), `INV-RAIL-04` (restated: a clearing position is (purpose,
counterparty) for counterparty-owned purposes; never nets two counterparties), `INV-REC-06`
(the position identity and completeness per counterparty position, every account of a
reconciled purpose walked), `INV-SET-07` (the `CURRENCY_NOT_SETTLED` refusal at screening),
`INV-LED-04` (the registry and chart are the ledger's; settlement and reconciliation hold
copies and ids only), `INV-LED-06` (each new purpose's type and normal balance decided in its
admitting migration, before any line).

## Follow-up

- `P9-TSK-010`: ledger `V021` (`OwnerKind.COUNTERPARTY`, `ledger.counterparty`, the four
  constraints restated, the `owner_ref` trigger); `ChartOfAccounts.resolve` by counterparty;
  `CounterpartyChartGuard`; the counterparty parts of `OperationalChartMigrationTest`;
  `SettlementSourceDescriptor.settledCounterparty` and `SettlementSources.of` keyed;
  `PositionProof` `PROVEN` derived from the register; completeness over every account of a
  reconciled purpose; `EverySettlingPositionHasASourceTest` per counterparty; the
  `CLEARING_POSITIONS` rule amended; `CounterpartyClearingIsNamedByDeclarationsTest`.
- `P9-TSK-011`: ledger `V022` (`FX_PROVIDER_CLEARING`, counterparty `fx-sim-a`, five
  accounts); settlement `V015` (`FX_PROVIDER_REPORT`, `SIM_FX_CSV`,
  `RejectionCode.CURRENCY_NOT_SETTLED`, source `fx-sim-a.trade-report`); it joins
  `reconciledPositions()` (ADR-0076 point 5).
- `P9-TSK-014`: ledger `V024` (`CORRIDOR_CLEARING`, counterparty `corridor-sim-a`, three
  accounts); settlement `V016` (`SIM_CORRIDOR_CSV`, source `corridor-sim-a.settlement`)
  (ADR-0080).
- `P9-TSK-026` (owner decision O8: cut first): ledger `V025` and settlement `V017` — the
  `-b` counterparties, proving each settles on its own position and never nets.
- `P9-TST-001`: the storm's position, cash and completeness proofs per counterparty, every
  round and at rest.
- *As built by `P9-TSK-010` (2026-10-05):* sections 2-6 and 8-9 are implemented - ledger `V021`
  (`OwnerKind.COUNTERPARTY`, the registry with SELECT and INSERT grants and an append-only trigger
  for every writer, the four rules restated, the `owner_ref` trigger leaving a NULL to the
  `CHECK`), `ChartOfAccounts.resolve(uow, purpose, counterpartyCode, currency)` with the
  two-argument form refusing, `CounterpartyChart` + `CounterpartyChartGuard` (a reachable chart
  with a gap refuses startup; an unreachable database defers to the per-call refusal),
  `SettlementSourceDescriptor.settledCounterparty`/`settledCurrencies`, `SettlementSources.of`
  keyed per (purpose, counterparty), recognitions and remittances on the counterparty's account,
  `PositionProof`'s proven purposes derived from the register and completeness over every account,
  the `CLEARING_POSITIONS` rule amended to a set of declaring files per purpose, and
  `CounterpartyClearingIsNamedByDeclarationsTest`. **One deviation:** `V021` also admits the
  purpose `FX_PROVIDER_CLEARING` (type ASSET pinned), with no registry row and no account, so every
  rule is proven against a real counterparty-owned purpose; `V022` admits `fx-sim-a` and its five
  accounts as section 4 says. Section 7 (`CURRENCY_NOT_SETTLED`) landed with `P9-TSK-011` (2026-10-05): refused at the parse leg, retained, never readmitted; and `fx-sim-a` was admitted - ledger `V022`, its source `fx-sim-a.trade-report` read off `FxProviderDeclaration`.
- The Phase 9 review reads this ADR against the code before accepting it (`P9-DOC-001`).
