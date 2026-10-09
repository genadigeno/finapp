# ADR-0085 — Credit data collection: provider-neutral ports, a normalised attribute vocabulary, consent checked twice, encrypted evidence with a stored deadline, and freshness on the database clock

Status: Proposed
Date: 2026-10-07
Phase: 10
Context: Credit · Consent · Platform · Security
Supersedes: nothing. Resolves `docs/adr/README.md`'s anticipated Phase 10 decision "Bureau
adapter and credit-data retention". Applies ADR-0008 (providers behind anti-corruption
adapters), ADR-0046 (no transaction spans a provider call), ADR-0038 (a provider's answer is
evidence) and ADR-0081's screening shape (requests born on a unique reference, a leaderless
retry sweep over database-stamped permits, the answer applied under the row lock and
conditional) to credit data. Reuses ADR-0066's envelope encryption under its own key purpose.
Rests on `PHASE_10_PLAN.md` §2, §5, §7, §8, §11, §12.2, §14 and `INV-CRD-03`, `-07`, `-08`,
`-10`.

## Context

A decision is only as defensible as the data it used. Collecting that data is where the phase
meets the outside world, and every hazard of the outside world applies:

1. **Bureaus and financial-data providers are unreliable** (`CLAUDE.md` rule 9): slow, down,
   duplicated, partial, malformed, or answering a status nobody documented. Their vocabulary
   (field names, score scales, account codes) differs per provider and must not leak into the
   rules (ADR-0008).
2. **A pull is a regulated act.** Retrieving someone's bureau record without a current lawful
   basis is unlawful whatever the platform later does with it (`INV-CRD-03`); and consent can be
   withdrawn while a pull is in flight.
3. **A pull is also a counted act.** Bureaus count (and charge for) pulls; a lost response retried
   naively is a second pull, and on some bureaus a second hard inquiry on the applicant's file.
4. **The evidence is financial PII** — the most sensitive data the platform will hold. It must be
   retained for explanation and dispute, but not forever, and not readable by the application at
   large.
5. **Stale data must never decide** (`INV-CRD-08`), and "stale" is a window — which Phase 8 and
   `X-TSK-013` proved can only be judged on the database clock across N instances.
6. **N instances retry concurrently.** Ten instances retrying one unavailable source must ask the
   provider once per attempt and record one answer.

## Decision

1. **Two provider-neutral ports, one adapter per provider.** `CreditBureau` and
   `FinancialDataProvider`, declared in `credit`, each with a sealed result: an answer (raw bytes
   plus the provider's reference), `UNAVAILABLE` with a cause (timeout, refused, 5xx, unknown
   status, malformed), never an exception escaping into the domain. Each adapter maps the
   provider's world totally into the port's — an answer the adapter cannot classify is
   `UNAVAILABLE`, never data (`INV-LIFE-03`). Adapters are simulated in Phase 10 (real bureau
   connectivity is out of scope, plan §17); each passes the port's contract suite (normal,
   partial, malformed, timeout, duplicate, unknown status) with normalisation golden files per
   adapter. A second bureau and source selection (`P10-TSK-021`) was the phase's first cut
   candidate; it was built, not cut (point 10): `bureau-sim-b` passes the same contract suite.

2. **The data request is born on a unique reference and asked with no connection held**
   (ADR-0046, ADR-0081's shape).
   - **Tx1** opens a `data_request` per source kind the request's pinned policy reads
     (`REQUESTED`, `request_reference UNIQUE`, the source kind, the provider code, a
     database-stamped permit, and the source kind's collection deadline and retry cadence —
     configuration, stamped on the row at its birth so a configuration change never moves an open
     request's deadline), checks consent (point 4) and audits the access as
     `credit.BureauDataRequested` / `credit.FinancialDataRequested` (`INV-AUD-01`: the access is
     the act). The data request carries `decision_request_id NOT NULL`; its foreign key arrives
     with the `decision_request` table (`P10-TSK-014`), which this table precedes.
   - **The wire**, holding no transaction: the adapter asks under our reference, with a bounded
     timeout. The provider dedupes by our reference, so a retry after a lost response asks again
     *under the same reference* and receives the first answer — one pull counted at the provider.
   - **Tx2** locks the data request, re-checks consent (point 4) and applies the outcome by a
     conditional transition: `REQUESTED → RECEIVED` with its `credit_record` born once
     (`UNIQUE (data_request_id)`), or `REQUESTED → UNAVAILABLE`, or
     `REQUESTED → CONSENT_WITHDRAWN`. Each attempt is an append-only `data_request_attempt` row.

3. **The machine: `REQUESTED → RECEIVED | UNAVAILABLE | CONSENT_WITHDRAWN`;
   `UNAVAILABLE → REQUESTED`** (a retry — a new attempt under the same reference) until the
   source's deadline; **`UNAVAILABLE → CONSENT_WITHDRAWN`** (a retry is gated like the first
   ask). `RECEIVED` is terminal and born-once-backed by its record; a duplicate answer (retry,
   duplicate delivery) finds the request already `RECEIVED`, and its evidence is kept *as a
   duplicate*, never a second record. A source still unavailable at its deadline stays
   `UNAVAILABLE`; the retry sweep emits `CreditDataUnavailable` for it exactly once (a
   conditional flag), and the decision request proceeds with the source `ABSENT` under the
   policy's fallback (point 7). The three-layer discipline holds the machine for every writer: a
   generated `CHECK`, an every-writer edge trigger, the domain. The full machine is in
   `CREDIT_DECISIONING_LIFECYCLES.md`.

4. **Consent is checked at both ends of every pull, each time in the acting transaction**
   (`INV-CRD-03`), through the `CreditConsentGate` port. The source kind maps to its purpose —
   bureau → `CREDIT_BUREAU_ACCESS`, financial data → `FINANCIAL_DATA_ACCESS`. The gate is read
   when the data request is opened (Tx1), at every retry, and again when the answer is recorded
   (Tx2), each a plain authoritative read under `READ COMMITTED`. A withdrawal before the answer
   is recorded yields `CONSENT_WITHDRAWN`: the payload is discarded unread, and the evidence row
   records only that a response arrived. **A data request ending `CONSENT_WITHDRAWN` abandons its
   decision request** — `ABANDONED`, reason `CONSENT_WITHDRAWN`, nothing decided (ADR-0087 §1;
   failure scenario 7). **A withdrawal after the answer was recorded** leaves the data request
   `RECEIVED` (terminal); the gate is re-read for every source kind at the freeze and in the
   deciding transaction, and a withdrawal found there abandons the request the same way, nothing
   frozen or decided (failure scenario 31). The platform neither decides on data it no longer has
   a basis to hold nor treats a withdrawal as an outage for the fallback to judge. A decision
   request whose consent is absent
   at submission is refused `403 credit.ConsentRequired`, the purpose named, before any provider
   is asked (plan §11, failure scenario 8). Consent never stands in for authorization
   (`INV-IDN-04`).

5. **Raw evidence is retained encrypted, unreadable by the application, with its deadline
   stored.**
   - `credit_evidence` holds the raw answer as received, encrypted with the platform's envelope
     encryption (ADR-0066's scheme: AES-256-GCM, a fresh nonce, the key version recorded, the
     associated data binding the row to its data request and attempt) under its **own key
     purpose `credit-evidence`** — never another module's key. `INSERT` only; `SELECT` revoked
     from the application role; an operator reaches it only through a definer function the
     evidence-read door calls (`POST /v1/operator/credit/records/{id}/evidence-read`,
     `CREDIT_INVESTIGATE`), with a reason, audited `credit.EvidenceRead`.
   - Every evidence row carries **`retain_until` = retrieval + the product's declared evidence
     retention** (default 25 months — a configuration of the product, ADR-0084 §6, not of the
     code). The normalised attributes inside a decision snapshot are retained for the decision's
     explanation life (ADR-0087).
   - **No purge runs in Phase 10.** Crypto-shredding and purge are Phase 15's
     operational-readiness work, recorded as a debt row with that owner. Phase 10 makes the
     deadline a stored, queryable fact so the purge has something exact to act on.
   - Classification: the payload and every attribute value are `RESTRICTED-FINANCIAL`; party
     references `CONFIDENTIAL` (`DATA_CLASSIFICATION.md`, `ColumnClassificationTest`). No
     attribute or payload appears in a log line, metric tag, span, event or exception message.

6. **Answers are normalised into a closed attribute vocabulary; nothing else reaches a rule.**
   A `credit_record` holds the attributes of one answered request, each with a code from the
   closed `CreditAttributeCode` vocabulary (plan §12.2: `BUREAU_EXTERNAL_SCORE`,
   `BUREAU_ACTIVE_ACCOUNTS`, `BUREAU_DELINQUENCIES_24M`, `BUREAU_DEFAULTS_72M`,
   `BUREAU_INSOLVENCY_FLAG`, `BUREAU_MONTHLY_OBLIGATIONS`, `BUREAU_TOTAL_BALANCE`,
   `FINDATA_MONTHLY_INCOME`, `FINDATA_MONTHLY_COMMITTED_EXPENDITURE`, `DECLARED_MONTHLY_INCOME`,
   `DECLARED_MONTHLY_EXPENDITURE`, `PARTY_AGE_YEARS`, `PARTY_RESIDENCY_COUNTRY`,
   `PLATFORM_OUTSTANDING_CREDIT` - *added by `P10-TSK-010`, ADR-0088 §6* - `PLATFORM_RESERVED_EXPOSURE`,
   `RISK_SIGNAL`), a typed value (integer, decimal-with-currency,
   boolean, code) and a provenance (the `credit_record` id and source, `DECLARED`, or the port
   and its version). Provider vocabulary stops at the adapter. A new attribute is a reviewed
   code change to the vocabulary.
   - **Partial data** (failure scenario 2): an attribute the provider did not supply is
     `ABSENT` — a value the policy reasons about explicitly (`IS_ABSENT`), never a default.
   - **Malformed data** (scenario 3): `UNAVAILABLE` with the evidence kept; never parsed into
     attributes.
   - **A source in another currency** (scenario 28): normalised as partial data — the attribute
     `ABSENT` with the recorded `CURRENCY_NOT_SUPPORTED` marker, an attribute value and not an
     error code — and never converted (no `ExchangeRate` anywhere in credit, `INV-CRD-12`).

7. **Unavailability and partial answers never approve** (`INV-CRD-10`). A source unavailable
   past its deadline reaches the snapshot with its attributes `ABSENT` and a recorded
   `SOURCE_UNAVAILABLE` attribute; the policy's declared fallback (`REFER` or `DECLINE`, never
   approve) fires with reason code `CRD-SOURCE-UNAVAILABLE`. ADR-0086 §6 makes the fallback
   mandatory at proposal.

8. **Freshness is judged on the database clock, at the freeze** (`INV-CRD-08`). Every record a
   snapshot uses must have been retrieved within the policy's declared maximum age for its source
   kind, judged on `statement_timestamp()` / `DatabaseTime.now` inside the freezing transaction —
   never an instance's clock. A record one second past the maximum age is not frozen: the request
   takes **`READY → COLLECTING`** — the machine's one backward edge, taken before any snapshot
   exists (scenario 9) — and the source is re-collected under a new data request (a `RECEIVED`
   one is terminal). The maximum age is the parameter of the policy version pinned on the request
   at `SUBMITTED → COLLECTING` (ADR-0086 §7), so the sources collected and the age they are judged
   by come from one version; an instance ±5 s skewed neither accepts stale data nor refuses fresh (plan
   §13). A record that arrives after the freeze belongs to no snapshot (the freeze reads the
   records under the request's row lock and fixes the snapshot by its unique, plan §7).

9. **The retry sweep is leaderless over database-stamped permits.** `CreditDataRetrySchedule`
   (a `SmartLifecycle` on `scheduleWithFixedDelay`, every instance, off in test contexts, its
   `finapp.credit.data.retry.sweeper.enabled` gauge) takes data requests `UNAVAILABLE` or
   `REQUESTED` past their permit, oldest permit first, in one
   `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` that stamps the new permit from
   `statement_timestamp()` at the data request's stamped cadence. A request that cannot act
   re-stamps rather than holding the page (the P9-TST-001 starvation lesson).

10. **Source selection: a configured order per source kind, the provider fixed at birth, no
    failover under a reference** (*added by `P10-TSK-021`, 2026-10-08*). Each kind has a
    provider order, the codes disabled, and a fail-safe (`CreditDataCollection.Configured`).
    - **Selection is a pure function of configuration, applied once, in Tx1**: the first
      provider in order not disabled, else the fail-safe (`bureau-none`, `findata-none` — every
      pull `UNAVAILABLE`). The chosen code is stamped on `data_request.provider_code` (frozen by
      the edge trigger since `P10-TSK-006`) and named in `credit.BureauDataRequested` /
      `credit.FinancialDataRequested`; no instance state, no rotation, no load balancing.
    - **Every ask goes to the provider the request was born naming** — the first and each retry,
      on whatever instance claims it. Disabling a provider stops new births only: an open request's
      retries keep its provider, because its reference is that provider's idempotency key and a
      lost response must be re-asked of the provider that may have answered it. A provider the
      asking instance no longer configures (a redeploy, a rolling deploy with diverging
      configuration) is `UNAVAILABLE(PROVIDER_ERROR)` **without a call** — never a substitute
      under the same reference. Tx2 refuses an answer naming another provider than the request's
      (`INV-CRD-07`: the record names the provider its request was born naming).
    - **Mid-request failover is refused** (Alternatives). An unavailable source reaches its
      deadline and the policy's fallback (point 7); both providers disabled, or the selected one
      unavailable, is `UNAVAILABLE` and never data. A stale record re-collected at the freeze
      (point 8) is a successor data request under a new reference and is selected afresh.
    - **Configuration** (`app`): `finapp.credit.<bureau|findata>.providers` and
      `.disabled`. Until unresolved questions #13 and #14 are answered, any provider named is
      refused at startup — the order is empty and every birth names the fail-safe; the selection
      is proven against `bureau-sim-a` and `bureau-sim-b` (`BureauSelectionDatabaseTest`).
    - **`bureau-sim-b`** speaks a wire sharing no word with `bureau-sim-a`'s (snake_case,
      epoch-second retrieval, a `Y`/`N` marker, amounts as minor units with their code) and
      normalises one person to the same attribute codes and values; each adapter's vocabulary is
      confined to its own file (`CreditProviderVocabularyIsConfinedTest`). Replay reads stored
      attributes, so the provider never matters to a past decision.

## Alternatives Considered

### Fail over mid-request to the next provider (`P10-TSK-021`)
Pros:
- A decision would wait for one provider's outage window, not fall to the fallback.

Cons:
- The selected provider's unavailability is known only at its deadline; failing over then
  doubles the collection window, which the decision request's own validity bounds.
- It pulls the person's file from a second bureau — a second processing act and, on some
  bureaus, a second inquiry — for a request the first bureau may yet have answered (a lost
  response): exactly the double pull point 2 exists to prevent.
- Done under the same reference it would put two providers' answers behind one key; done under
  a new reference it needs a successor-request edge the machine does not have.
- Operations already have the lever that matters: disabling a provider moves every new birth
  at once.

Refused (point 10). If a later phase takes it, it is a successor data request under a new
reference, never a second provider under one.

### One port per provider, with provider fields reaching the rules
Pros:
- No normalisation layer; rules read exactly what the bureau said.

Cons:
- Every rule becomes provider-specific; adding a bureau rewrites the policy, and a policy version
  can no longer be replayed against a snapshot from a different provider.
- ADR-0008 forbids provider vocabulary in the domain.

Refused (points 1, 6).

### Check consent once, at submission
Pros:
- One read; simpler.

Cons:
- A withdrawal between submission and the answer would still put the applicant's bureau data
  into the platform after the basis ended. The gate read in the recording transaction is what
  makes `INV-CRD-03` true for the *retention*, not only for the *request*.

Refused (point 4).

### Keep evidence readable by the application for convenience; purge in Phase 10
Pros:
- Simpler explanation door; the retention promise kept from day one.

Cons:
- Application-wide `SELECT` on bureau payloads widens the PII surface to every query path.
- A purge without crypto-shredding, key rotation and backup handling is a partial promise that
  looks complete; that machinery is Phase 15's.

Refused: no application `SELECT`, the deadline stored, the purge deferred with a named owner
(point 5).

### Default a missing attribute (zero, false, the population mean)
Pros:
- Every rule always has a value; fewer refer outcomes.

Cons:
- A default silently decides — a missing delinquency count defaulting to zero approves on data
  that does not exist (`INV-CRD-10`), and the snapshot would not show the gap.

Refused: `ABSENT` is a value the policy must reason about (point 6).

## Consequences

Positive:
- A pull is lawful at both ends, counted once per reference, audited as an act, and replayable
  from normalised attributes whose provenance is stored.
- A provider outage, a partial or malformed answer, or an unknown status can only ever refer or
  decline — never approve — and the reason says so.
- The purge Phase 15 builds has an exact, per-row deadline to act on.

Negative:
- An outage delays decisions to the source's deadline and then refers or declines them — a cost
  of fail-safe, visible on `finapp.credit.data.request{outcome}` and its unavailability alert.
- Evidence accumulates without purge until Phase 15, recorded as debt.
- The normalisation layer and its golden files are per-adapter work.

Operational impact: `finapp.credit.data.request{source_kind, provider, outcome}`,
`finapp.credit.data.latency{source_kind, provider}`, the retry sweeper's enabled gauge; no
amount, attribute or party in any tag.
Security impact: the payload is `RESTRICTED-FINANCIAL` ciphertext under a dedicated key, no
application `SELECT`, read only through an audited definer function with a reason; the
`INV-RAIL-03` needle walk extends to credit's doors; records' `toString` names identifiers only.
Financial impact: none posted. Provider pull costs are bounded by the reference dedupe.

## Invariants / Constraints

`INV-CRD-03` (consent in the opening and the recording transaction), `INV-CRD-07` (every
attribute with its provenance), `INV-CRD-08` (freshness on the database clock at the freeze),
`INV-CRD-10` (unavailability never approves), `INV-CRD-12` (no conversion), `INV-LIFE-03` (an
unknown provider answer is a modelled state), `INV-AUD-01` (every access audited),
`INV-CNS-01` (the gate), `INV-CNS-02` (consent history append-only), `INV-IDN-04`, ADR-0008,
ADR-0038, ADR-0046, ADR-0066, ADR-0081.

## Follow-up

- `P10-TSK-004`: the credit profile (born once per party). `-005`: the ports, the simulated
  bureau, normalisation and its golden files, the contract suite. `-006`: the data request, the
  consent checks at open, retry and record, the retry schedule, duplicates and lost responses.
  `-007`: the financial-data provider. `-008`: the freeze and its freshness judgement. `-015`,
  `-016`: the gate re-read at the freeze and in the deciding transaction (scenario 31). `-017`:
  the evidence-read door. `-021` (built 2026-10-08, not cut): a second bureau and source
  selection (point 10).
- Phase 15: evidence purge and crypto-shredding against `retain_until` (debt row).
- *As found by `P10-TST-001` (2026-10-09)*: "the latest data request of a kind" - which the freeze and the
  `COLLECTING -> READY` step both read - is ordered by `requested_at`, the database's statement clock, and that clock
  steps back. A re-collection born inside a step read as older than the stale request it replaced, and was re-collected
  again until the clock passed it: redundant paid pulls, never a stale decision. `credit V015` stamps each birth after
  the decision request's latest (`GREATEST(statement_timestamp(), latest + 1 µs)`, serialised by the request's row
  lock, ADR-0063 decision 2's permit form). Proven by
  `DecisionSnapshotDatabaseTest#aReCollectionOnAClockBehindItsStaleRecordIsTheLatest`, red first on `V004`'s stamp.
- **Acceptance.** The Phase 10 review (`P10-DOC-001`) reads this ADR against the code before
  accepting it.
