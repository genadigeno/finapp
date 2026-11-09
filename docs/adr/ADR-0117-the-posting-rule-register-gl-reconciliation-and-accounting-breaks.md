# ADR-0117 — The posting rule register, GL ↔ ledger ↔ source reconciliation, and accounting breaks

Status: Accepted (2026-11-06, `P14-DOC-001` — read against the code and corrected first)
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
   *(Corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: the counts above are their tasks' own. The
   register now stands at **catalogue version 4** (`PostingRuleRegister.CATALOGUE_VERSION`,
   `PostingRuleRegister.java:35-40`): version 3 added `ACC-PRIOR-PERIOD`, keyed by the proposal's origin `ACCOUNTING`
   (`P14-TSK-013`), and version 4 the two write-offs `LND-14` and `BNPL-F11` (`P14-TSK-021`, C6) - **61 rules, 68 rule
   versions** (`PostingRuleCatalogueTest`), the adjustment rules keyed by the three origins `MANUAL`, `RECONCILIATION`
   and `ACCOUNTING`. `ECONOMIC_DATE` is still lending's accrual and the manual adjustment; **ten** rules are now
   `STORED_ROW`, the prior-period adjustment the tenth (`PostingRuleCatalogue.java`).)*
2. **Three checks per period**, each a close check: GL = ledger (mapping totality, no double mapping, sums equal per
   currency); rule conformance (namespace registered, shape conforming); source completeness (the existing proofs'
   verdicts through `SourceProofs`, never re-implemented).
3. **Accounting breaks** (owner decision C11): `accounting_break` born once per `(period, check, subject, generation)`,
   types `MAPPING_GAP`, `DOUBLE_MAPPING`, `UNREGISTERED_POSTING`, `SHAPE_MISMATCH`, `UNBALANCED_ENTRY`,
   `SOURCE_DISCREPANCY`, `SOURCE_DELAYED` (declared, never raised: no built proof tells a delay from a discrepancy);
   `OPEN → ACKNOWLEDGED`, and `OPEN` or `ACKNOWLEDGED → RESOLVED`; acknowledgement four-eyes with a disclosure; resolution
   only by reference to a correcting entry posted through the owning domain's door. Never deleted, never self-corrected.
   *(Corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: this read "born once per `(period, check,
   subject)`", "`SOURCE_DELAYED`" unqualified and "`OPEN → ACKNOWLEDGED → RESOLVED`". `P14-TSK-012`'s `V008` took the
   generation into `accounting_break_born_once`'s key (`V008:35-38`; the Open questions' first entry). `SOURCE_DELAYED`
   is in `BreakType` and never raised (`SourceCompleteness.java:20`; accrual lag is the close run's `ACCRUALS_COMPLETE`).
   An `OPEN` break may be resolved without first being acknowledged: `V006`'s edge trigger admits `OPEN` and
   `ACKNOWLEDGED` alike to `RESOLVED` (`V006:165`, its refusal naming the edges at `V006:174`).)*
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
   *(`P14-TSK-012`, 2026-11-03: a month's run judged by the check on the owner's rule, and narrowed to the floored, `PHASE_14_PLAN.md` §13.5's note.)*
5. *(Dated note, 2026-11-09, `P15-TSK-035`: the source-join census is a new reader of this decision's register, not a new
   decision.* It joins every journal entry to the domain record that caused it, namespace by namespace, from
   `PostingRuleRegister.CURRENT`: decision 2's "the judges read only" and its port-and-compose shape, kept. `accounting`
   owns the census's comparison and its own records (`V015`); each posting module owns one `*JournalSources` read port over
   its own tables only, on `platform`'s `sourcejoin` contract; `app` alone composes them (`SourceCensusComposition`),
   exactly as `ProductSourceProofs` composes the proofs. No new cross-module edge: a module reads no other module's
   tables, `SourceJoinCoverageTest` holds it, and no ADR-0131 was opened. *Findings are a detective control and never a
   break of this register's kind, so a finding has no lifecycle here. The walk's position is `(txid, id)` in the ledger's own terms (ledger `V034`, this task's fix round; ADR-0014's rule that no correctness argument depends on clock skew): the column and its stamping trigger change no poster and no judgement.)*

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

*(As built, `P14-TSK-020`, 2026-11-05: `AccountingBreakAged` reads a new `finapp.accounting.break.aged{type}`, breaks `OPEN` past `finapp.accounting.break.aged-after`, on the database's clock, and not `finapp.accounting.break.open`: an `ACKNOWLEDGED` generation, superseded or not, was decided under four eyes and can stand for good, so an alert on the count would fire for good and be muted.)*

*(Note, 2026-11-06, `P14-TSK-025`; the owner's rulings of that day on `P14-TSK-012`'s (a), (c), (e), (h) and (k); no new decision, so no new ADR.)* **A source proof's evidence compares only what is failing.** "Unchanged" stays exact string equality (`Held.changedBy`, `V008`'s trigger), so the evidence `ProductSourceProofs` renders now keeps what names each failing arm and item and drops what moves while the failure does not: a loan's and an obligation's `status=`, `POSITION`'s open expectation and item counts and the suspense's open items, `CASH_AT_BANK`'s `latestSequence=`, and `LOAN_REPLAY`'s `findings=`, which a divergence of one kind adds to with every new fact; `BNPL_THREE_WAY`'s two failing counts are joined by the failing merchant legs and purchases by id, so one fixed and another broken no longer reads the same, and the counts and a SHA-256 of the two whole sorted lists stand ahead of the names, so a failure past the 2000-character cut still reads changed: the ids sort in their creation order and the newest is always last (the fix round's I-1; every other reworded proof is bounded well under the cut by its arms' names). `LENDING_CAPITAL`'s `unexplainedEntries=` stays: its proof names no entry, and capital's lines post only in a statement's acceptance beside their recognition (reconciliation `V022`), so the count moves only when an entry no recognition explains appears or goes; dropping it would let a second raw posting ride on the first's acknowledgement. Still reading the same, accepted: an amount alone (the owner's limit above); **a position's or the suspense's whole further life in the month once acknowledged**, its evidence being one fixed string a subject (`position explained=false`, `suspense explained=false`), so new open expectations or items on it, new money movements and not only amounts, ride on that acknowledgement until the month's next run of the check finds the subject explained or the month moves on (the fix round's M-2, the ruling read exactly); and a replay divergence of a kind already named on another fact of the same loan. The format changed once: a decided break of the five reworded proofs judged before it reads as changed once, a new generation needing its four eyes, as (h) accepted. `JUDGED_BY` stays `source-proofs:v1`, the catalogue being unchanged. **Which months require a source's run** (`RECONCILIATION_DISCLOSURE`, its re-judge and `finapp.accounting.close.month.unrun`, one rule, `ReconciliationRunCoverage.Source#requiredIn` through `Coverage#required`): one this build declares, admitted by the month's last day and not retired before its first (settlement `V018`'s `admitted_on` and `retired_on`, the eight standing sources admitted `1970-01-01`, before any month, set by migration only and moving only towards requiring more), and whose rule set stood active by the month's last day - the first activation of a version, a person's four-eyes act, or for reconciliation `V002`'s seeded versions the source's admission. Dated, so for a month that has ended only a migration's stated days move the answer, and they can move it either way, closed or not: an admission dated into the month requires it, a retirement dated before it or an admission dated after it excuses it (the fix round's M-1). That is why only a reviewed migration states them, and why `V018` refuses a retirement that a writer able to state its day leaves undated (M-3).


## Follow-up

Line-shape conformance for amounts (e.g. fee ≤ gross) as rule predicates, if the battery shows value.

## Open questions

- **Resolved. As built by `P14-TSK-012`, 2026-11-03, accounting `V008`**: `accounting_break.generation`, in `accounting_break_born_once`'s key `(period_start, check_code, subject_ref, generation)`, rows already in the table generation 1 by the column's default; the trigger allows a later generation only over the one before it, decided and reading differently, and lets a racer through to `ON CONFLICT DO NOTHING`, so ten workers judging one changed finding insert one row. `held` answers each subject's latest generation. An `OPEN` latest raises nothing; a decided latest is compared by **exact string with `BreakFinding`'s normalised evidence**, so "unchanged" holds no clock and no walk order: the first entries of a namespace are the first by (posting date, id), currencies, keys and lists sorted. Unchanged counts as disclosed (`knownAcknowledged`, `knownResolved`); changed is `FINDING_CHANGED`, a new break, `FAILED` until decided. `RECURRED` and `ACKNOWLEDGED_CHANGED` are gone, and `CloseChecks.verdictOf` asks `decided()`. **An overtaken worker**: its generation number comes from what it read, so it can never mint an older generation, and once that generation exists it writes nothing; if it is first, its raise is a real finding from a real snapshot and only makes the close harder, fail closed, and `CloseRuns`' re-judging of `OPEN_ACCOUNTING_BREAKS` (`-011`'s M-1) births the attempt that shows it; the approval then refuses. *(Corrected by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06: "the approval then refuses" holds only for a raise that commits before the approval's read. A raise takes no period row, so an overtaken worker's can commit after that read and land as an `OPEN` break on a month just `CLOSED` - never hidden (listed and aged by `finapp.accounting.break.aged` as any `OPEN` break), but not stopping a close already approved; `PeriodCloses`' class javadoc states the window. **The owner accepted this limit on 2026-11-06** (`P14-TSK-012`'s item (i), `DECISIONS.md` "The close's open questions"): such a break may stand `OPEN` on a `CLOSED` month.)* **Limits, stated**: no amounts (the owner's); a difference past `BreakFinding`'s 2000-character cut is unseen; a release that changes an evidence format reads as changed, one more acknowledgement; **and `SOURCE_COMPLETENESS`'s evidence is a proof over the live book**, so more than the discrepancy can move it: `POSITION`'s `openExpectations=` and `openItems=` and `CASH_AT_BANK`'s `latestSequence=`, a loan's or an obligation's `status=`, `LOAN_REPLAY`'s `findings=`, `LENDING_CAPITAL`'s `unexplainedEntries=`, `BNPL_THREE_WAY`'s `merchantLegsFailing=` and `identitiesFailing=`. Under traffic such a subject can read as changed between an acknowledgement and a re-run, the same discrepancy a new generation each time (an owner item). Tests: `AccountingChecksTest#aChangedFindingIsANewGeneration`, `#aResolvedBreaksUnchangedFindingCountsAsDisclosed`, `#aResolvedBreaksChangedFindingIsANewGeneration`, `#workersReadingOneHeldRaiseOneGeneration` (sequential; the ten-way race is the database case), `#aLaterGenerationFollowsADecidedOne`, `#theComparisonIsCanonical`, `PeriodCloseMigrationTest#theGenerationsMigration`, `PeriodCloseDatabaseTest#theGenerationInTheKey` (owed, C5).
- **Ruled by the owner, 2026-11-02: the two questions below are one rule, built by `P14-TSK-012`.** A finding is compared with the evidence a person decided
  on. **Unchanged**: it counts as disclosed - a `RESOLVED` break's by its correcting entry, so it no longer reads `RECURRED`; an `ACKNOWLEDGED` break's as today.
  **Changed**: it becomes a new break under a generation in the key, needing its own four-eyes acknowledgement; until then the check is `FAILED`. So an immutable
  subject no longer wedges a close, and a proof over the live book that moves has a door. **The limit, accepted**: evidence holds no amounts, so a change of amount
  alone is not seen. The rule changes no row of which checks may be `DISCLOSED`: `GL_EQUALS_LEDGER` and `LEDGER_TRIAL_BALANCE` never may. The two entries below
  stand as the question's history.
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
- **What an acknowledgement holds** (`P14-TSK-011`'s review, I-1; open, the same owner decision as the recurrence above) *(superseded by
  `P14-TSK-012`: ruled 2026-11-02 and built - the first two entries above; `ACKNOWLEDGED_CHANGED` no longer exists, a changed finding being
  `FINDING_CHANGED` and a new generation. Labelled by `P14-DOC-001`, the Phase 14 exit review, 2026-11-06; the text below is the question's
  history)*: four eyes acknowledge what a
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
  open. *(Ruled by the owner, 2026-11-06, `DECISIONS.md` "The close's open questions"; recorded by `P14-DOC-001`, the
  Phase 14 exit review: no door reverses an adjustment today, and a door reversing an adjustment, if ever built, reads
  its reversal by the original's origin. Every other reversal stays judged against its own `REVERSAL` rule, as built.)*

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
