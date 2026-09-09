# Phase 2 — KYC/KYB and Consent

Planned by the Phase 1 → 2 transition (2026-09-09,
[`reviews/PHASE_1_TO_2_TRANSITION.md`](reviews/PHASE_1_TO_2_TRANSITION.md)). Decisions in
ADR-0035 … ADR-0038; the properties this phase must protect are `INV-KYC-01`…`06` and
`INV-CNS-01`…`04` in [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md), plus
`INV-HIST-02`, `INV-IDEM-04`, `INV-LIFE-02`…`04` and `INV-AUD-01`…`03` from earlier phases.

---

## 1. Business scope

**Objective: a Party can be verified to the standard a regulator requires, with the evidence
retained and the decision defensible — and the platform holds a recorded lawful basis before it
processes for a purpose that needs one.**

By the end of Phase 2:

- a Customer's onboarding opens a **KYC case** whose lifecycle is explicit, whose checks are run
  through provider adapters, and whose decision is the platform's own recorded act;
- an organisation opens a **KYB case** that establishes the beneficial-ownership graph, each
  natural-person owner verified through the KYC mechanism;
- **screening** (sanctions, PEP, adverse media) runs at onboarding, and a hit is resolved by a
  person with elevated authorization — never by silence;
- **documents** are captured, encrypted, access-controlled and access-audited;
- **consent** is a purpose-scoped, versioned, append-only history with an enforcement gate other
  capabilities query;
- `party.customer.status` finally moves — `PENDING → ACTIVE`/`REJECTED` — as a **projection** of
  the KYC decision (ADR-0035), which is the onboarding gate every later phase queries.

Still no money, no account, no ledger.

## 2. Bounded contexts

| Context | Module | Role |
|---|---|---|
| KYC/KYB (3) | `kyc` | Owns cases, checks, evidence, documents, review, decisions |
| Consent (4) | `consent` | Owns the lawful-basis history and the gate |
| Party & Customer (1) | `party` | Consumes the verification outcome as a projection |
| Identity (2) | `identity` | Unchanged; supplies the proven actor for every privileged action |

`app` orchestrates the one cross-module transaction (decision → customer projection), the
registration precedent.

## 3. Capabilities — in and out

**In:** KYC case lifecycle; KYB with beneficial owners; document capture; identity/document
verification via simulated providers; sanctions/PEP/adverse-media screening; manual review with
reason codes; immutable decisions; customer-status projection; consent grant/withdraw/query and
the enforcement gate; the platform's first **broker adapter** (outbox → Kafka) and first
consumer, inherited from the transition.

**Out** (owning phase): ongoing transaction monitoring and rescreening automation (13);
behavioural AML (13); accounts/ledger/money (3+); credit bureau consent consumption (10);
jurisdiction-specific rule packs beyond the policy seam (per phase); a reviewer UI (operator
tooling, 15); real provider connectivity (never — ADR-0008 simulates).

## 4. Domain model

**`kyc` aggregates and entities**

- **KycCase** — one run of verification for one party. Holds status, the policy version it is
  assessed under, and its case events. One *open* case per customer at a time (DB-enforced).
- **KybCase** — the organisational variant; additionally owns the **BeneficialOwner** set: each a
  natural person with an ownership stake or control role, each requiring verification; the graph
  terminates in verified persons (GLOSSARY: *"recursive, and it terminates in Parties"*).
- **VerificationCheck** — one question to one provider: type (IDENTITY, DOCUMENT, SANCTIONS, PEP,
  ADVERSE_MEDIA), the evidence reference, and a **normalised outcome**: CLEAR, HIT,
  INDETERMINATE. Evidence verbatim (`INV-HIST-02`); outcome vocabulary ours (ADR-0008).
- **Document** — captured bytes: encrypted content, SHA-256 checksum, declared type, size-bounded
  (ADR-0036). Access-audited on read (`INV-KYC-06`).
- **ReviewTask** — the explicit work item a non-clean case becomes (`INV-KYC-04`).
- **KycDecision** — immutable, attributable, reason-carrying, policy-pinned, evidence-referencing
  (`INV-KYC-02`). APPROVED or REJECTED.

**`consent` aggregates**

- **ConsentText** — a versioned, immutable text for a purpose; whether a version requires
  re-consent is a property of the version (`INV-CNS-04`).
- **ConsentRecord** — an immutable fact: GRANT or WITHDRAWAL, (party, purpose, text version,
  instant). Current basis derived, never stored (ADR-0037).
- **ConsentPurpose** — a closed enumeration in code (first members: `KYC_PROCESSING`,
  `SCREENING`). A free-string purpose is a vocabulary nobody controls.

## 5. Lifecycles

**KycCase / KybCase** (one machine; KYB adds the ownership precondition to decisioning):

```
OPEN → CHECKS_IN_PROGRESS → { READY_FOR_DECISION | IN_REVIEW } → { APPROVED | REJECTED }
```

- `OPEN → CHECKS_IN_PROGRESS`: first check dispatched.
- `CHECKS_IN_PROGRESS → READY_FOR_DECISION`: every check terminal and CLEAR.
- `CHECKS_IN_PROGRESS → IN_REVIEW`: any check HIT, or INDETERMINATE past its retry budget
  (`INV-KYC-04` — silence resolves nothing).
- `IN_REVIEW → READY_FOR_DECISION`: every review task resolved by a reviewer.
- `READY_FOR_DECISION → APPROVED | REJECTED`: the decision is recorded; **terminal**
  (`INV-LIFE-04`). Changed circumstances open a *new* case.
- Every invalid pair rejected **by the aggregate** (`INV-LIFE-02`), exhaustively tested — the
  Phase 2 gate's first bullet.

**VerificationCheck**: `REQUESTED → DISPATCHED → { CLEAR | HIT | INDETERMINATE }`; INDETERMINATE
is terminal for the check — resolution is a *new* check on the same case (`INV-LIFE-03`: never a
decision by assumption, and a check never flaps).

**ReviewTask**: `OPEN → RESOLVED(reason, reviewer)`; terminal; no unresolve — a wrong resolution
is a new review event on the case.

**Screening** is a VerificationCheck of the three screening types — one machine, not a second
one.

**ConsentRecord** has no machine: records are immutable facts; the *derived* basis flips between
granted and withdrawn as records append.

## 6. Security model

- Sensitive data: document content and screening evidence `RESTRICTED-PII` at the ceiling
  (ADR-0022); consent history `CONFIDENTIAL`; classification rows land with each migration or
  the build fails (`ColumnClassificationTest`).
- Encryption: document content AES-256-GCM under `FINAPP_DOC_KEY`, externalised, published local
  default confined to loopback — the `INV-IDN-08` mechanism reused (ADR-0036).
- Privileged surface: reviewer decision endpoints behind `@RequiresPermission(KYC_REVIEW)` — a
  new permission and the `KYC_REVIEWER` role, which finally makes the role→permission mapping
  mutation-testable (`P1-TSK-020`'s recorded limit).
- Ownership: a customer sees **their own** case status only (`SESSION_DERIVED`, the `/v1/me`
  shape) and never screening detail; reviewers name cases by identifier (`ADMINISTERED`).
- Audit: every case opening, check outcome, document access, review resolution, decision,
  consent grant and withdrawal is a catalogued auditable action (`INV-AUD-01`); reviewer actions
  require a reason.
- Enumeration: case-status responses to the customer are shaped so screening hits are
  indistinguishable from ordinary processing (`IN_PROGRESS` covers both) — `INV-IDN-07`'s
  reasoning applied to tipping-off.

## 7. API model

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /v1/me/kyc` | session | Opens the caller's case; idempotent per open case (one-open-case constraint is the mechanism) |
| `GET /v1/me/kyc` | session | Own case status; never screening detail |
| `POST /v1/me/kyc/documents` | session | Size/type-bounded upload onto the open case |
| `POST /v1/me/consents` | session | Grant for a purpose against the current text version |
| `DELETE /v1/me/consents/{purpose}` | session | Withdrawal — a new record, not a deletion |
| `GET /v1/me/consents` | session | Current basis per purpose |
| `GET /v1/kyc/cases/{id}` | session, `KYC_REVIEW` | Full case with evidence references; access audited |
| `POST /v1/kyc/cases/{id}/reviews/{taskId}/resolution` | session, `KYC_REVIEW` | Reason required; not-self rule inapplicable (subject is a case) |
| `POST /v1/kyc/cases/{id}/decision` | session, `KYC_REVIEW` | Records the immutable decision; transitions the customer projection in the same transaction |
| *(inbound)* `POST /v1/providers/kyc/callbacks` | signed/simulated | Provider result delivery; inbox-deduplicated |

Contract discipline unchanged: byte-for-byte OpenAPI comparison, every endpoint declares an
authorization rule, every request body bounded.

## 8. Failure scenarios

1. Provider timeout → check `INDETERMINATE`, case does not decide by assumption (`INV-LIFE-03`).
2. Provider answers after our timeout → late callback meets the inbox; a terminal check is not
   reopened — new-check resolution path.
3. Duplicate provider callback → one case advance (`INV-KYC-03`, inbox + conditional UPDATE).
4. Document upload succeeds, case update fails → one transaction; no orphaned evidence
   (atomicity test).
5. Two instances process the same callback → one effect (`P0-TST-009` convention).
6. Concurrent reviewer resolutions of one task → one wins by conditional UPDATE; the loser is
   told (409), one audit record.
7. Concurrent decision and new evidence → decision on a `READY_FOR_DECISION` case only;
   conditional transition arbitrates.
8. Consent withdrawal racing a gated operation → the gate reads authoritative state per decision;
   after the withdrawal commits, no new gated operation proceeds on any instance (`INV-CNS-03`).
9. Screening list updated after a decision → the decision stands (immutable); rescreening is
   Phase 13's; the seam (re-runnable check) is recorded.
10. Crash between decision recorded and projection updated → impossible: same transaction
    (ADR-0035); asserted by the atomicity test.
11. Duplicate case-open submissions (double tap, retry) → one open case, DB-enforced partial
    unique index; second answer names the existing case.
12. Broker adapter down → outbox holds; events are durable and unread; consumer lag is visible
    (`finapp.outbox.pending` already exists).

## 9. Testing strategy

The taxonomy is unchanged (ADR-0028). Per tier:

- **Unit**: lifecycle machines (every state pair, from the machine, the `P1-TSK-005` idiom);
  outcome normalisation; consent derivation; policy evaluation.
- **Architecture**: `kyc`/`consent` isolation both directions; no provider vocabulary in domain
  or contract (extend the ADR-0008 boundary test); `secretsAreWrapped` and ownership
  classification cover the new modules automatically (derived coverage — asserted, not hoped).
- **Database**: every endpoint over HTTP; evidence append-only privileges; document plaintext in
  no column (`information_schema` sweep); duplicate callbacks; one-open-case under 10-way race;
  review authorization negative tests; consent withdrawal blocking the gated capability across
  instances; decision/projection atomicity under kill.
- **Mutation demonstrations**: every `INV-KYC-*` and `INV-CNS-*` gains a register row as its
  enforcement lands; the register guard begins demanding them the day Phase 2's status flips
  `COMPLETE` (the transition's guard redesign).
- Provider failure modes drive through `SimulatedProvider` (`P0-TSK-037`) — the harness was built
  for exactly this phase's arrivals.

## 10. Observability

| Meter | Why |
|---|---|
| `finapp.kyc.case` — counter by outcome (opened, approved, rejected) | Case throughput; the straight-through rate is derivable |
| `finapp.kyc.check` — counter by outcome (clear, hit, indeterminate) | Screening hit rate; a rising `indeterminate` is a provider degrading |
| `finapp.kyc.review.queue` — gauge | Manual review depth; the queue nobody watches is the queue that ages |
| `finapp.kyc.provider.latency` — timer | Provider latency and error visibility per ADR-0008 |
| `finapp.consent.grant` — counter by purpose | Consent coverage movement |
| `finapp.consent.withdrawal` — counter by purpose | A withdrawal spike is a trust event worth seeing |

Counters registered eagerly (`P1-TSK-029`'s rule); names satisfy `MetricNames.NAME` (dots, no
underscores — checked here at planning time, which is the drift that plan's table shipped with).
`PlannedMetersExistTest` enforces this table from the day Phase 2 is recorded `COMPLETE`.

## 11. Milestones

| Milestone | Contents | Acceptance |
|---|---|---|
| **M2.1 — Foundations settle** | Broker adapter + first consumer; `P1-TSK-033`; module skeletons and schemas | An outbox event reaches a real consumer through Kafka exactly once per fact; a person can change their password |
| **M2.2 — A case exists and checks run** | KycCase aggregate + lifecycle; documents; provider adapters; callbacks | A case opened over HTTP reaches `READY_FOR_DECISION` on clean simulated checks, with evidence retained verbatim |
| **M2.3 — Decisions and review** | Review tasks; reviewer endpoints; immutable decision; customer projection | A hit case cannot terminate without a reviewer; a decision moves `customer.status` in one transaction |
| **M2.4 — KYB and ownership** | KybCase; beneficial owners; owner verification | An organisation's case decides only when every owner's verification is terminal |
| **M2.5 — Consent** | Texts, records, gate, endpoints | Withdrawal demonstrably blocks the dependent capability, across instances |
| **M2.6 — Phase review** | `P2-DOC-001` | The exit gate assessed with evidence, and a verdict |

## 12. What Phase 2 must not implement

| Must not | Why, and what is permitted instead |
|---|---|
| **Accounts, wallets, ledger, any money** | Phase 3+ |
| **Ongoing monitoring / rescreening automation** | Phase 13. The seam: a check is re-runnable against a case; no scheduler exists |
| **Behavioural AML** | Phase 13 |
| **Credit bureau consent consumption** | Phase 10 consumes the `consent` mechanism; no bureau purpose is enumerated yet |
| **A rules engine** | ADR-0038; policy is versioned code, pinned per decision |
| **A reviewer UI** | Operator tooling, Phase 15; the API is the deliverable |
| **Real provider connectivity** | Never (ADR-0008); `SimulatedProvider` is the counterparty |
| **Object storage** | ADR-0036; deferred with a named trigger, `DocumentStore` is the seam |
| **Four-eyes second-approver modelling** | Recorded debt (ADR-0010); the reason requirement is the enforceable part today |
