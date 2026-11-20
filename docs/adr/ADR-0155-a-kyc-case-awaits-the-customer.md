# ADR-0155 — A KYC case awaits the customer: a reviewer's request for information, its answer and its expiry

Status: Accepted (2026-11-20, at `P17-TSK-126`'s gate; written by its design, every open choice taken as the recommended option under
the owner's standing ruling of 2026-11-20, "If you rise questions, my answer is your recommended options")

Date: 2026-11-20

Phase: 17 (`P17-TSK-126`; finapp-admin-panel `docs/FINAPP_BACKEND_WORK.md` B6.6 item 3, gap G-63; the owner's D-25 decision 3,
2026-11-19: "request more information from the customer, in Phase 17")

Context: KYC (the case machine, `kyc`) · the reviewer's and the customer's doors (`app`)

Rests on: ADR-0035 (the KYC review and its "Single reviewer" amendment), ADR-0004 (idempotency keys), ADR-0010 (audit), ADR-0075
section 5 (expiry as a leaderless sweep on the database clock), `INV-KYC-02`, `INV-KYC-04`, `INV-LIFE-02`, `INV-LIFE-04`.

## Context

A KYC case goes to `IN_REVIEW` when a check hits or stays indeterminate (`INV-KYC-04`), and a reviewer resolves each review task and
then decides. A reviewer who cannot judge without the customer - an unreadable document, a name that differs from the document, a
missing proof of address - has no move today but to resolve on what they have. Every onboarding operation needs the ordinary
"request for information": the case waits on the customer, with a deadline, and comes back to the reviewer.

Two things make it more than a status. First, **tipping-off**: the case is in review because some check raised something, and that
check may be sanctions, PEP or adverse media; the request's text is the one channel from the review surface to the subject, so what
it may carry must be enforced, not documented. Second, **the expiry must be correct with N instances**: a deadline judged by an
instance's clock, or a scheduler elected to one instance, is the single-instance assumption `CLAUDE.md` forbids.

## Decision

1. **The state and the named transition table.** `KycCaseStatus` gains `AWAITING_CUSTOMER`, non-terminal (so the one-open-case
   index and `-029`'s queue index already hold it). The machine's edges are one named table, `KycCaseTransition`, from which
   `permittedTransitions()` is derived:

   | Transition | From | To | Driven by |
   |---|---|---|---|
   | `BEGIN_CHECKS` | `OPEN` | `CHECKS_IN_PROGRESS` | the first check dispatched |
   | `CHECKS_CLEAR` | `CHECKS_IN_PROGRESS` | `READY_FOR_DECISION` | every check terminal and clear |
   | `CHECKS_RAISE_REVIEW` | `CHECKS_IN_PROGRESS` | `IN_REVIEW` | a hit, or an indeterminate past its budget |
   | `REVIEW_COMPLETE` | `IN_REVIEW` | `READY_FOR_DECISION` | no review task left open |
   | **`INFORMATION_REQUESTED`** | `IN_REVIEW` | `AWAITING_CUSTOMER` | a reviewer's request (this ADR) |
   | **`CUSTOMER_ANSWERED`** | `AWAITING_CUSTOMER` | `IN_REVIEW` | the customer's answer (this ADR) |
   | **`REQUEST_EXPIRED`** | `AWAITING_CUSTOMER` | `IN_REVIEW` | the request's deadline passing (this ADR) |
   | `APPROVE` | `READY_FOR_DECISION` | `APPROVED` | the decision |
   | `REJECT` | `READY_FOR_DECISION` | `REJECTED` | the decision |

   Nothing else leaves `AWAITING_CUSTOMER`: no decision and no readiness while the customer holds the case. The aggregate's methods
   name their edge (`awaitCustomer`, `resumeReview`), and `requireReview` is `CHECKS_RAISE_REVIEW` alone, so no method reaches
   `IN_REVIEW` by the other edge. Every pair outside the table is refused by the aggregate (`INV-LIFE-02`), swept in its test, and by
   the store's conditional move before any statement runs (`JdbcKycCaseStore.moveStatus`), so no caller moves a case off the table.

2. **The request is an entity of the case**, `kyc.information_request` (kyc `V016`): a reason code (`InformationRequestReason`,
   internal), a text for the customer, the requesting reviewer, `requested_at`, `due_at`, and `OPEN → ANSWERED | EXPIRED`, terminal.
   **At most one `OPEN` per case** (a unique partial index), and **a case is `AWAITING_CUSTOMER` exactly while it has one**: every
   edge writes both rows in one transaction. A trigger keeps the request's identity and text immutable and lets it close once.

3. **The reviewer's door**: `POST /v1/kyc/cases/{id}/information-request` under `KYC_REVIEW`, on a `MULTI_FACTOR` session, keyed
   (`Idempotency-Key`, scoped to the reviewer: a replay answers the first `204`, the key under another request `409 api.Conflict`, a
   refusal spends no key), `{reasonCode, requestText}`, every refusal recorded (`@RecordsRefusals`, never the body). One
   transaction: the case's conditional `IN_REVIEW → AWAITING_CUSTOMER`, the request's insert, `kyc.InformationRequested` (the reason
   code its reason), the outbox event. `409` for a case not in review, already awaiting the customer, or a KYB case (decision 9).

4. **Tipping-off is enforced in the domain.** The text is a `CustomerRequestText`, which cannot be constructed: outside 20 to 1,000
   characters or with a control character; carrying screening, suspicion or investigation vocabulary (sanctions, PEP, politically
   exposed, watch list, adverse media, screening, OFAC and the other list authorities, money laundering, terrorist financing,
   suspicious activity, fraud investigation, law enforcement, financial intelligence, frozen assets, and the like - matched as whole
   words, or at a word's start for a stem, after Unicode normalisation, case folding and punctuation folded to spaces); carrying an identifier the platform mints (a UUID); or carrying a link. The
   door answers `422 kyc.RequestTextRefused`, naming the rule, never echoing the text. The reason code never reaches the customer,
   and its vocabulary names what the customer must supply, never why. A request is offered only on a case in review, so the
   customer-facing status `ACTION_REQUIRED` says that the customer is needed - as every onboarding's document request does - and
   nothing about which check raised it.

5. **The notification seam.** The platform has no notifier (`notification` deferred, `P15-REM-001`); its seam is the transactional
   outbox, whose integration events the notifier is declared to consume (`MODULE_ARCHITECTURE.md` section `notification`). The request
   writes `kyc.CustomerInformationRequested` v1 (aggregate `KycCase`: `caseId`, `customerId`, `requestId`, `dueAtEpochMilli` - the platform's form for an instant in a payload) - **no text and
   no reason**: a notification body crosses a channel the platform does not control (`INV-AUD-02`), so the notice says only that
   something is needed and by when. The text is read on the customer's proven session: `GET /v1/me/kyc` answers `ACTION_REQUIRED` and
   `informationRequest {requestText, dueAt}` while one is open. Until the notifier exists the customer learns in the app.

6. **The customer's answer is a new door**: `POST /v1/me/kyc/information-response`, the `/v1/me` shape (no identifier: the session
   resolves the customer, the customer their open case, the case its open request), keyed per customer, `{answer}` (1 to 2,000
   characters, the customer's own words, RESTRICTED-PII, shown to reviewers only). One transaction: the request's conditional
   `OPEN → ANSWERED` - **refused once `due_at` has passed on the database clock**, so whether an answer counts never depends on when a
   sweep ran - the case's `AWAITING_CUSTOMER → IN_REVIEW`, `kyc.InformationRequestAnswered`. `409 kyc.NoOpenInformationRequest` when
   there is nothing to answer. Documents go first through the existing `POST /v1/me/kyc/documents`, which admits any open case.

7. **The expiry is leaderless on the database clock** (ADR-0075 section 5, `FxQuoteExpirySchedule`'s pattern): every instance runs
   `InformationRequestExpirySchedule`, no lease; each page is one transaction whose conditional `UPDATE` closes the requests `OPEN`
   with `due_at <= statement_timestamp()`, rows another sweeper holds skipped (`FOR UPDATE SKIP LOCKED`), and returns each case
   `AWAITING_CUSTOMER → IN_REVIEW` with `kyc.InformationRequestExpired` under the platform's actor. Ten sweepers close ten pages of
   distinct rows; an answer and a sweep meet on the request's row lock, one wins. The window is 14 days from the request
   (`InformationRequest.ANSWER_WINDOW`), a policy value changed in review like the policy version.

8. **A return re-asks readiness.** After the answer's or the expiry's commit the case's `IN_REVIEW → READY_FOR_DECISION` exit is
   attempted, as a resolution's is: a reviewer may have resolved the last task while the customer held the case (a resolution is
   case-status-blind), and without the re-ask such a case would rest `IN_REVIEW` with nothing to resolve. The exit still requires no
   `OPEN` task (`INV-KYC-04`); the decision stays the reviewer's separate act (`INV-KYC-02`).

9. **KYB is out of this decision.** A KYB case's customer is an organisation, reached through `/v1/me/kyb`; a request on one is
   refused `409` until that surface has an answer door - a follow-up. The owner store already admits a declaration on a case
   awaiting its customer (the set freezes at `READY_FOR_DECISION`, as before), so that follow-up needs no second change there.

10. **The reviewer sees every request** - its reason, text, deadline, status and the customer's answer - in the case file
    (`GET /v1/kyc/cases/{id}`'s `informationRequests`), and `-029`'s queue filters by `AWAITING_CUSTOMER`.

## Alternatives Considered

### The answer implied by a document upload
Pros: no new door. Cons: a request may need several documents, or an explanation and none; the first upload would hand the case back
incomplete, and a request answered by words has no door at all. Rejected.

### The answer through `POST /v1/me/kyc`
Pros: an existing door. Cons: its meaning is "ensure my case exists", converging; an answer is an act with content. Rejected.

### The text in the notification event
Pros: a notifier could deliver it whole. Cons: an email or SMS body is an unencrypted channel to an address the platform does not
control (`INV-AUD-02`); the in-app read is on a proven session. Rejected.

### A reviewer-chosen or configured deadline
Pros: flexibility. Cons: a deadline per reviewer is a policy nobody reviewed; a configured one differs by environment. A fixed window
in the domain is changed by a reviewed change. Rejected for now.

### An answer admitted until a sweep runs
Pros: kinder at the edge. Cons: the outcome would depend on when a sweeper happened to run. Rejected: one clock, the database's,
judges both.

### A single-instance scheduler (`@Scheduled` on a leader)
Rejected by `CLAUDE.md` and ADR-0075: correctness may not rest on one process.

### A request on a KYB case now
Pros: complete. Cons: needs a KYB answer door and its authorisation through the declarant; a request nobody can answer would wait out
its deadline. Deferred, refused meanwhile.

## Consequences

Positive: the reviewer can ask instead of guessing; the text cannot tip off by construction; expiry is correct at any instance count.
Negative: one more state for every exhaustive switch over the case's status; KYB waits for a follow-up.
Operational impact: a new leaderless schedule (`finapp.kyc.information-request.expiry.*`), on by default, off in test contexts.
Security impact: one new operator door (factor, key, permission, refusals recorded) and one customer door (session-derived, keyed);
the request text screened; the answer RESTRICTED-PII.
Financial impact: none - no balance, no posting, no money moves.

## Invariants / Constraints

`INV-LIFE-02` (the table, refused by the aggregate); `INV-LIFE-04` (a request closes once; the case's decided states stay terminal);
`INV-KYC-02` (the decision unchanged and separate); `INV-KYC-04` (a return re-asks readiness, which needs no open task);
`INV-IDEM-*` (both doors keyed); `INV-AUD-02` (no text in the event or the logs).

## Follow-up

- A KYB answer door under `/v1/me/kyb`, then requests on KYB cases.
- The notifier (`notification`, deferred) consuming `kyc.CustomerInformationRequested`.
- A reviewer's withdrawal of an open request, if operations ask for one.
- The database tier owed by the owner's ruling of 2026-11-20: V016 on PostgreSQL, the sweep's `SKIP LOCKED` race, the answer and
  expiry race, both doors end to end.
