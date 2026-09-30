# ADR-0066 — Raw settlement files are screened at the door, authenticated by pull credential or second-person attestation, and retained encrypted in PostgreSQL behind a port

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Settlement · Reconciliation · Security
Supersedes: nothing. Re-assesses ADR-0036 (bytes in PostgreSQL behind a port, object storage
deferred) for its first high-volume subject, and replaces its trigger with named ones for
settlement files. Rules that `INV-PAY-02` and `INV-RAIL-03` take precedence over `INV-HIST-02`
for a refused delivery. Amends `DELIVERY_PLAN.md` §Phase 8.6, `SECURITY_ARCHITECTURE.md`:119 and
`MODULE_ARCHITECTURE.md`'s `settlement` Security line.

## Context

Phase 8 brings the platform's first external inflow that is a file rather than a message: four
counterparty sources (the card PSP's settlement report, the instant scheme's cycle report, the
payout provider's report and the settlement bank's statement), delivered as simulated CSV, JSON
and MT940-shaped formats. These bytes move money. An accepted report recognises the
counterparty's fees and opens its remittance expectation; an accepted statement moves
`CASH_AT_BANK` against a clearing position (ADR-0065). What may enter, who vouches for it, and
where it rests are therefore financial controls, not storage details.

Four texts already in the repository pull in different directions:

| Text | Says |
|---|---|
| `INV-HIST-02` | Settlement files are "retained unmodified, with a checksum" |
| `MODULE_ARCHITECTURE.md`, the `settlement` Security line | Settlement files "may contain PII — encrypted, access-controlled, retained verbatim" |
| `INV-PAY-02` | No PAN is "stored, logged, transported or exposed anywhere in the platform" |
| `INV-RAIL-03` | Values shaped like an account number, an international account identifier or an alias are "refused at the surface" |

Real settlement files carry names, account identifiers and, in free-text descriptors, sometimes
card numbers. Retaining such a file verbatim stores what `INV-PAY-02` and `INV-RAIL-03` forbid;
refusing it gives up verbatim evidence. The texts conflict, and one of them has to yield by
ruling rather than by whichever task meets the case first.

Storage has the same kind of drift. `DELIVERY_PLAN.md` §Phase 8.6 reads "raw retained in object
storage with checksum", and `SECURITY_ARCHITECTURE.md`:119 assigns "Object storage (settlement
files, documents)" to Phase 8. But `compose.yaml` runs PostgreSQL, Kafka and Redis and nothing
else, and ADR-0036 deferred object storage with a trigger: "document volume becoming operationally
material, or Phase 5's evidence retention arriving". Phase 5's evidence retention did arrive, and
its payloads went into PostgreSQL too (`payments.provider_evidence`, bounded at 1 MiB by
`ProviderEvidenceStore.MAX_PAYLOAD_BYTES`), but no document records that re-assessment.
Settlement files are larger, daily and per source, so the trigger has to be re-assessed now, with
thresholds someone can measure. The Phase 1 → 2 transition corrected the same aspirational line
for documents (ADR-0036); this ADR does it for settlement files.

Authentication is new too. A webhook proves its origin by signature (ADR-0047). An operator's
upload proves only who the operator is. With no second control, one insider holding the upload
permission could upload a fabricated PSP report and a fabricated bank statement and discharge a
clearing position into fictitious cash. All three candidate designs' reviewers flagged this path.
It is `INV-PAY-01`'s concern applied to files.

Finally, none of the platform's existing ciphers (`EvidenceCipher`, `PayoutEvidenceCipher`,
`DocumentCipher`, `SecretCipher`) binds associated data. A ciphertext the key genuinely wrote
decrypts cleanly wherever it is placed; only the whole-payload checksum notices a substitution.
A settlement file is stored in chunks, so a chunk's position is part of what must be proven.

Until Phase 8's first task lands, nothing in this ADR is implemented; every statement is the
decided design, corrected by the tasks that build it.

## Decision

1. **Every delivery enters through one door, `FileReception.receive`, reached by three
   channels.**

   | Channel (`received_via`) | Who delivers | What authenticates it | Acceptance |
   |---|---|---|---|
   | `PULL` | The platform: `SettlementPullSchedule`, or an operator's `POST /v1/operator/settlement/sources/{code}/fetch` under `SETTLEMENT_INGEST` (audited `settlement.SettlementFetchRequested`) | The source's own confined credential | Accepted once parsed |
   | `UPLOAD` | A person holding `SETTLEMENT_INGEST`: `POST /v1/operator/settlement/files` (source code, declared business date, base64 content) → `202 {fileId, status, duplicateOf?}` | A **second** person holding `SETTLEMENT_INGEST`, distinct from the uploader, attests it (point 2) | Inert until attested |
   | `READMISSION` | A person holding `RECONCILIATION_ADMINISTER`, reasoned: `POST /v1/operator/settlement/files/{id}/readmission` | Inherited from the original, whose checksum it shares, when the original was pulled or attested; otherwise a second person's attestation of the readmission itself (point 8) | Accepted when the original was pulled or attested, or once the readmission is attested |

   The door works in a fixed order: the source is known and `ACTIVE` (`settlement.SourceUnknown`,
   `settlement.SourceRetired`); the size bounds hold (point 4); the screen passes (point 3); the
   content address is free (point 5). Only then does one transaction store the file row
   (`RECEIVED`, `received_at` from the server clock), its encrypted chunks, a `NEW` receipt, the
   upload's idempotency record and the audit record (`settlement.SettlementFileUploaded`, or
   `settlement.SettlementFileReceivedByPull` for the platform, acting-only). The pull holds no
   database connection across the provider call (ADR-0046). Pull clients are `app` adapters of
   `SettlementReportCollector` (ADR-0008's SPI shape).

   **Each pull source's URL joins `ProviderTransportGuard`'s one list.** Since the Phase 7 → 8
   transition's repair, the application refuses to start when any provider base URL is not
   `https` off loopback (`SECURITY_ARCHITECTURE.md`, the provider-transport row). A settlement
   pull reads bytes that move money over a confined credential, so the guard is extended to it
   (`P8-TSK-021`). A pull source whose URL is neither `https` nor `sftp` off loopback refuses
   startup the same way. The simulated sources stay on loopback, as every simulated provider
   does.

   Push delivery (a counterparty-signed callback, the webhook door's shape) is deferred.

2. **An upload is inert until a second person attests it** (`INV-SET-07`, `INV-AUD-04`).
   - Attestation is a `NULL → value` fact (`attested_by`, `attested_at`), settable while the file
     is `RECEIVED` or `PARSED` and never on a terminal file (`settlement.FileNotAttestable`). It is
     recorded through `POST /v1/operator/settlement/files/{id}/attestation`, audited
     `settlement.SettlementFileAttested`.
   - The uploader cannot attest their own file. This is refused at the domain
     (`settlement.AttestationBySubmitter`) and at the database:
     `CHECK (attested_by IS NULL OR attested_by <> received_by)` and
     `CHECK (status <> 'ACCEPTED' OR received_via <> 'UPLOAD' OR attested_by IS NOT NULL)`.
     The accept leg (P8-TSK-009) accepts an upload only once it is attested.
   - Before attesting, the attester may read the parsed totals and, through the audited content
     read (point 7), the bytes themselves. The attester or the uploader may instead decline:
     `POST .../decline {reason}` takes the file to `REJECTED` (`DECLINED`), reasoned and audited
     (`settlement.SettlementFileDeclined`).
   - Racing attesters, or an attestation racing a decline, serialise on the file row
     (`FOR UPDATE`) and a conditional `NULL → value` write: exactly one attestation, and each loser
     gets a 409 or converges.
   - Person-distinctness is by actor id. The known debt that operators are audited as `CUSTOMER`
     (`CURRENT_STATE.md`, owned by Phase 15) does not weaken it, because the file row holds both
     ids.
   - **Authentication follows the row's channel, not its receipts.** A pull that meets an
     unattested upload's bytes appends a `DUPLICATE` receipt and does not authenticate the upload,
     because the `CHECK` reads `received_via`. A second upload of the same bytes by another person
     is a receipt too, not an attestation: attesting is an explicit, audited act. The file waits
     for its attester, visibly (`finapp.settlement.file.pending`, `finapp.settlement.file.age`).
   - Pulls authenticate with four per-source credentials (P8-TSK-021):
     `FINAPP_SETTLEMENT_PSP_REPORT_KEY`, `FINAPP_SETTLEMENT_SCHEME_REPORT_KEY`,
     `FINAPP_SETTLEMENT_PAYOUT_REPORT_KEY` and `FINAPP_SETTLEMENT_BANK_STATEMENT_KEY`. They are kept
     apart from the money-moving provider keys on purpose: reading a report is not the
     money-moving API, and one key per concern lets each rotate, and leak, alone.

3. **The door screens by field class, in memory, before anything is stored.** The screen is
   `SettlementFormat.screen(byte[])`, defined per format version. It is pure: no I/O, no clock,
   no database.
   - **Bytes that parse structurally** are checked field by field against each field's declared
     class:
     - reference fields must match their shape patterns (the payments `V015`/`V017` precedents),
       so an acquirer reference number, or a 15-digit network transaction id that happens to be
       Luhn-valid, is never tested as free text;
     - amounts and dates are checked by type;
     - only the adapter's declared **free-text fields** (descriptors, remittance text, names) are
       screened for Luhn-valid runs of 13 to 19 digits and for international account identifier,
       account-number or alias shapes;
     - **a field that fails its declared class is screened as free text** before the file can be
       stored as malformed. A card number sitting in a reference column is therefore refused, not
       retained inside a file the parse leg will later reject.
   - **Bytes that do not parse** are screened as one conservative stream. A clean malformed file
     is stored and then rejected by the parse leg, because it is the evidence of a corrupt
     delivery. A dirty one is refused.
   - Until P8-TSK-008 ships the first format, P8-TSK-002's door runs the conservative byte-level
     screen alone.
   - What the canonical records keep is bounded as well. `settlement.line` holds types,
     directions, amounts, dates and fingerprints. `settlement.line_reference` holds typed
     references (all `CONFIDENTIAL`), each shape-checked, with bank-identifier and alias shapes
     refused by `CHECK` (`INV-RAIL-03` at the database rank). Free text stays only inside the
     encrypted file.

   **Screening refuses what must never enter; encryption and audited reads protect what may
   enter but is sensitive.** A name in a bank statement is personal data, not instrument data. It
   passes the screen and rests encrypted as `RESTRICTED-PII` (point 7).

4. **A refused delivery keeps its metadata and never its value.** This is the precedence ruling.
   - A refusal writes, in one transaction, only a `settlement.refused_delivery` row (source,
     SHA-256, length, format version, reason, line number, field name, channel, actor,
     correlation), the audit record `settlement.SettlementDeliveryRefused`, and a count on
     `finapp.settlement.delivery.refused`, which is alertable. The caller gets
     `settlement.DeliveryRefused`. The offending value is written nowhere: not in the row, the
     audit record, a log line, an error body or a metric.
   - **For a refused delivery, `INV-PAY-02` and `INV-RAIL-03` take precedence over
     `INV-HIST-02`.** `INV-HIST-02` exists so that a decision can be defended from the evidence it
     was taken on. A refused delivery decides nothing: it posts nothing, allocates nothing and
     opens nothing, and the counterparty still holds its own bytes. `INV-PAY-02` says "anywhere",
     and a PAN encrypted at rest is still a PAN at rest, bringing the settlement store into PCI
     scope. `INV-RAIL-03` says "refused at the surface". The metadata row proves what was
     refused, when, from whom and why, and its checksum ties the refusal to any later
     re-presentation. `INV-HIST-02` gains a note recording the ruling. Its Verify, for every
     delivery that is stored, is that the stored bytes equal the received bytes by checksum.
   - **Recovery is by re-presentation.** The counterparty re-issues the file, or a new format
     version corrects a false positive. A pull re-lists its lookback window; an upload is sent
     again. A refusal occupies no content address, so the same bytes can be presented again and
     are screened under whatever version is then current. The refused row and the later file
     share the checksum, so the chain stays visible. For real formats, the recorded requirement
     is a tokenising pre-processor outside the platform's boundary.
   - **Size bounds.** A file is at most 8 MiB of decoded content (`content_length` 1..8,388,608)
     and 50,000 lines, the line count taken by the screen's own walk of the records. A delivery
     over either bound is refused at the door with `413 settlement.FileTooLarge`. Nothing is
     stored, and the refusal is audited. The bounds exist because parse and acceptance are each
     one transaction (point 9); they are revisited with the storage triggers (point 6).

5. **Duplicates converge on the content address.**

   | Case | Arbiter | Outcome |
   |---|---|---|
   | The same bytes from the same source, by any channel, ten times | `UNIQUE (source_id, content_sha256) WHERE readmits_file_id IS NULL` | Each loser appends a `DUPLICATE` `file_receipt` against the existing row and is answered with it (`duplicateOf`). Nothing else is written |
   | The same upload retried | The idempotency record under `settlement.upload:<actorType>:<actorId>`, per principal from birth, committed with the receive | Replayed; the same key with a different body is a 409 (`INV-IDEM-03`) |
   | Different bytes declaring a live batch, or a statement sequence already accepted | The live-batch uniques on `settlement.batch` (ADR-0065) | `REJECTED` (`CONFLICTING_BATCH`): retained, alerted, never applied; readmissible once the conflicting batch is `REPUDIATED` (point 8) |
   | The same line twice, in one file or across files | `canonical_fingerprint`, indexed and deliberately not unique | Both kept; the second becomes `DUPLICATE_EXTERNAL` at matching (ADR-0068, ADR-0069) |

   A rejected or declined file keeps its content address. Re-delivering its bytes is a duplicate
   and changes nothing. Readmission (point 8) is the only way the same bytes are parsed again.

6. **Admitted files are retained encrypted in PostgreSQL, behind the `SettlementFileStore`
   port.** This is ADR-0036 re-assessed.
   - The bytes are held in `settlement.file_chunk`, one row per chunk: PK `(file_id, seq)`,
     `ciphertext`, `nonce bytea(12)`, `plaintext_length ≤ 1048576`. The 1 MiB chunk is the
     provider-evidence bound. Each chunk is AES-256-GCM with a fresh random nonce, and the key
     version is recorded on the file.
   - **The associated data binds `file_id ‖ source_id ‖ content_sha256 ‖ seq`**, each at its
     fixed width so the concatenation is unambiguous. A chunk therefore decrypts only in its own
     file, source, content and position; placing it anywhere else is a decryption failure at that
     chunk. Three separate questions get three separate answers. GCM answers *did this key write
     this ciphertext*. The associated data answers *was it written for this file, this source,
     this content and this position*. The checksum answers *are these the bytes received*. This
     is `DECISIONS.md`'s distinction for documents, extended by one question.
   - The whole-plaintext SHA-256 (`content_sha256`, 32 bytes) is verified on every read. On a
     mismatch nothing is served, and the failure is audited.
   - The bytes commit with their metadata in the receive transaction. There is no orphan object,
     no half-written file, and no file row without its bytes.
   - The cipher is `SettlementFileCipher` and the key is `SettlementFileKey`:
     `FINAPP_SETTLEMENT_FILE_KEY`, a `KeySpec` with domain suffix `/settlement-file` and
     `EXACTLY_32`, following the `DisputeEvidenceKey` precedent. It is the platform's twelfth
     confined credential, with a published local default confined to loopback, and it is never
     the provider-evidence, dispute-evidence or document key.
   - **Why PostgreSQL:** one store, one privilege model and one backup; bytes that commit
     atomically with their metadata; append-only enforceable at `DB-PRIVILEGE` today; and no
     fourth infrastructure component for simulated volume.
   - **Triggers to revisit:** any one of
     - a source's daily volume above 256 MiB,
     - total retained settlement evidence above 50 GiB,
     - a real format that exceeds the per-file bound,
     - production deployment.

     These replace ADR-0036's "operationally material" for settlement files. ADR-0036's own
     trigger for documents and provider evidence is unchanged.
   - Moving to object storage is an adapter change behind `SettlementFileStore` plus a data
     migration. The associated data binds identifiers, not a storage location, so it survives the
     move.

7. **Least privilege, and every content read audited** (`INV-REC-10`, the regime of
   `INV-KYC-06` and `INV-DSP-03` applied to this phase's evidence).
   - **Grants.** `finapp_app` holds `SELECT, INSERT` and nothing more on `file_chunk`, `line`,
     `line_reference`, `file_receipt`, `refused_delivery`, `ingestion_error` and the histories.
     Each table also has an append-only trigger that refuses every writer. On `settlement.file`,
     `UPDATE` is narrowed to `(status, rejection_code, rejection_detail, parse_failures,
     next_parse_at, attested_by, attested_at, status_changed_at)`, behind the transition trigger.
     Nothing in the schema grants `DELETE`.
   - **One read path for content.** The only way to read content is
     `POST /v1/operator/settlement/files/{id}/content-reads {reason}` under
     `RECONCILIATION_INVESTIGATE`. It verifies the checksum and writes
     `settlement.SettlementFileContentRead` for each read, with the reason, in the read's own
     transaction. The audited unit is the access (ADR-0036), and a guessed identifier records
     nothing. Metadata reads (`GET .../sources`, `.../files[/{id}]`, `.../refused-deliveries`)
     carry no content and sit behind the same permission.
   - **Classification** (`DATA_CLASSIFICATION.md` §4 rows in the same change):
     - content and ciphertext are `RESTRICTED-PII`;
     - references are `CONFIDENTIAL`, like the existing rows labelled Phase 8's match key;
     - amounts are `RESTRICTED-FINANCIAL`;
     - `rejection_detail` is at most 500 characters and carries no content;
     - `ingestion_error` keeps at most 100 rows per file (line, code, field) and never a value.
   - **The existing sweep and needle are widened.** `PaymentEndpointDatabaseTest`'s column sweep
     and `PayByBankDatabaseTest`'s needle extend over both new schemas and a settlement flow. The
     needle rides a bank statement whose free text carries an international account identifier
     shape, and the delivery is refused.

8. **The format version is recorded on every file, and a wrongly rejected file is readmitted.**
   - Every file and batch records `format_id` and `format_version`. Each version is frozen by
     golden-file tests (the `RailMoneySemanticsArePinnedTest` precedent), and any change to a
     screen or a parser is a new version.
   - **Readmission** is for a file the parse leg rejected wrongly, where our validation was the
     defect. It is also for a `CONFLICTING_BATCH` original whose conflicting batch has since been
     `REPUDIATED` (`P8-TSK-023`). That rejection was right when it was made, and the repudiation
     removed its ground. This is how a genuine file refused beside a fabricated batch is
     recovered. Any other file is refused with `settlement.FileNotRejected`. A readmission is a new
     row naming the original (`readmits_file_id UNIQUE`). It is keyed, reasoned and audited
     (`settlement.SettlementFileReadmitted`), and parsed under the current format version.
   - Its bytes are the original's plaintext: decrypted, verified against the checksum, screened
     under the current version, and re-encrypted under the readmission's own id. The associated
     data binds every file's chunks to that file, and the original is not touched. If the current
     screen refuses the bytes, the readmission is refused like any other delivery, and the
     original stands.
   - The readmission inherits authentication because its checksum equals the original's, when
     the original was pulled or attested. Then it is accepted like its original.
   - **An unattested original passes no authentication on.** An upload the parse leg rejected
     before anyone attested it would otherwise never be accepted after readmission. Its
     readmission is inert until a second person holding `SETTLEMENT_INGEST` attests the
     readmission itself, exactly as for an upload (point 2). The attester must be distinct from
     the readmitter and from the original's uploader, so readmission is never a way round the
     second person. `P8-TSK-022` holds this at the domain and at the database, as point 2 does
     for uploads.
   - **A declined upload is not readmitted.** Declining is a person's judgement, not our
     validation. A wrongly declined file is recovered by the counterparty's re-issue. A
     byte-identical re-issue, though, meets the declined file's content address and is answered
     as its duplicate. Whether readmission extends to declined uploads is recorded for P8-TSK-022
     to decide, not decided here. If it does, such a readmission inherits no authentication and
     must itself be attested, under the rule for an unattested original above.
   - The **re-parse verification** (P8-TSK-022) recomputes a stored file's fingerprints under its
     recorded version and compares them with the stored lines. It never replaces lines.

   *(The unattested original and the `CONFLICTING_BATCH` readmission were added by the Phase 7 →
   8 transition's consistency review, A11. The rule had read "accepted only when the original was
   pulled or attested", which no unattested upload rejected at parse could ever meet. And a
   genuine file rejected `CONFLICTING_BATCH` before a repudiation was not our validation's
   defect.)*

9. **Evidence takes effect only whole, and our own failure never rejects it** (`INV-SET-07`).
   - Parse is one transaction. It produces the lines, references, batch and control totals
     (folded with `Money`, never SQL `SUM`), or up to 100 `ingestion_error` rows plus `REJECTED`
     with one of `MALFORMED`, `CONTROL_TOTAL_MISMATCH`, `UNKNOWN_CURRENCY`, `SCALE_MISMATCH`,
     `UNSUPPORTED_FORMAT` or `CONFLICTING_BATCH`. A rejection publishes
     `settlement.SettlementFileRejected` (fileId, sourceCode, rejectionCode), audited
     acting-only. Acceptance is one transaction too (ADR-0065), and the matcher reads only
     `ACCEPTED` batches. A partially corrupt file cannot drive half a settlement.
   - A parser exception is our defect, not the counterparty's. It leaves the file `RECEIVED`,
     with `parse_failures + 1` and `next_parse_at` backed off, visible as a stuck file on
     `finapp.settlement.file.age`. A rejected file's expectations age into `MISSING_EXTERNAL`
     breaks on schedule. They are never assumed settled.
   - The file's machine (`RECEIVED → PARSED → ACCEPTED`, `RECEIVED | PARSED → REJECTED`, with
     no other edges) is specified in `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` under the
     three-layer discipline: the aggregate, a generated `CHECK` with an every-writer transition
     trigger, and the append-only `file_event` history.

10. **Ten instances at the door.** Every contention names its PostgreSQL arbiter and has a
    counted ten-way test.

    | Contention | Arbiter | Loser |
    |---|---|---|
    | The same file received ten times, or by upload and pull at once | The content unique; the per-principal idempotency record | Converges, and writes a `DUPLICATE` receipt |
    | Concurrent parse of one file | `FOR UPDATE SKIP LOCKED` on `RECEIVED` files whose `next_parse_at` has passed; conditional `RECEIVED → PARSED`; `UNIQUE (file_id, line_no)` | Skips |
    | Two attesters, or attest against decline | The file row `FOR UPDATE`; the conditional `NULL → value`; the distinctness `CHECK` | 409, or converges |
    | Ten instances pulling one report | An idempotent GET; the conditional renewal of `pull_permit`, strictly advancing on every renewal (the send-permit shape), paces the herd; the content unique dedupes | No duplicate file |

    No correctness here depends on process memory, a leader, a lease clock or Kafka.

11. **The texts this amends,** each amended at the transition with provenance naming this ADR:
    - `DELIVERY_PLAN.md` §Phase 8.6, "raw retained in object storage with checksum", becomes
      PostgreSQL behind `SettlementFileStore`, encrypted, with the triggers of point 6. This goes
      in the Phase 8 addendum.
    - `SECURITY_ARCHITECTURE.md`:119. For settlement files, the "server-side encryption" row
      becomes application-level AES-256-GCM with bound associated data in PostgreSQL, a checksum
      verified on every read, and every content read audited. Object storage remains the target
      once a trigger fires.
    - `MODULE_ARCHITECTURE.md`'s `settlement` Security line, "retained verbatim", becomes
      "screened at the door; retained verbatim and encrypted when admitted; a refused delivery
      retained as metadata only".
    - `INV-HIST-02`'s note (point 4). The Verify lines of `INV-PAY-02` and `INV-RAIL-03` extend
      their sweep, their needle and the door refusal to settlement.

    **Owner decision O3** (refuse at the door rather than retain PII-bearing files verbatim) is
    settled on this ADR's recommendation at the transition, recorded as a transition decision the
    owner may revisit. So is **O6**: if scope must shrink, pull acquisition (P8-TSK-021) is the
    first cut, and upload with attestation alone satisfies `INV-SET-07`.

## Alternatives Considered

### Retain PII-bearing files verbatim, encrypted (two of the candidate designs; O3's alternative)
Pros: `INV-HIST-02` holds literally for every delivery. There are no false-positive refusals, and
the whole of a bad delivery stays available for investigation.
Cons: it stores card numbers and bank identifiers in the platform. Encryption at rest does not
change that, and it brings the settlement store into PCI scope, against `INV-PAY-02`'s
"anywhere" and `INV-RAIL-03`'s "refused at the surface". Every audited content read would then
show an operator raw instrument data. Rejected, and recorded as the owner's to revisit (O3).

### Screen the whole byte stream for Luhn-valid digit runs (the third design as first drawn)
Pros: format-agnostic, and simple to state.
Cons: reference values are digit strings, and a random digit string passes the Luhn check one
time in ten. Network transaction ids and acquirer references would refuse genuine reports
routinely. Field-class screening checks references by shape and screens free text; the
whole-stream screen remains only as the fallback for bytes that do not parse.

### Redact the offending values and store the remainder
Pros: most of the file is kept, and processing continues without the counterparty.
Cons: the stored bytes are then ours, not the counterparty's. The checksum no longer proves what
was received, which is the one thing `INV-HIST-02` asks for, and the platform ends up
interpreting a file around a hole. For real formats the recorded answer is a tokenising
pre-processor outside the boundary.

### Object storage now (the delivery plan's line)
Pros: the production shape; large bytes out of the relational store; server-side encryption.
Cons: a fourth pinned infrastructure component, and a second access-control and audit system, for
simulated files measured in kilobytes. Bytes and metadata would no longer commit atomically, so
orphaned and missing objects become failure modes, and append-only would have to be rebuilt as
object-lock policy. ADR-0036's argument holds at this volume. The triggers in point 6 say when it
stops holding.

### A single person's upload is authenticated (the third design)
Pros: one operator acts; lower latency; less operator burden.
Cons: a single insider fabricates a report and a statement and discharges clearing into
fictitious cash, and there is no counterparty signature to catch it. Attestation closes that path
for uploads, and the per-source credential closes it for pulls.

### Signed push delivery by the counterparty
Pros: authenticated by the counterparty itself, with no operator in the loop.
Cons: no simulated counterparty pushes files, and a push door is a new inbound surface with its
own keys and replay window. Deferred, together with real connectivity and multi-part files.

### Unbounded files with chunked, leased ingestion (the second design)
Pros: any file size.
Cons: leases, a `HELD` state and chunked discharge would all be needed to make half a file safe,
and the bounds make that machinery unnecessary. A control-total mismatch must also reject the
whole file (`MODULE_ARCHITECTURE.md`: "a partially corrupt file fails the batch"), which chunked
discharge contradicts. The bound is revisited with the storage triggers.

### Reuse the existing ciphers as they are, with no associated data
Pros: one mechanism, already tested.
Cons: a chunk the key genuinely wrote, placed in another file's row or at another position,
decrypts cleanly. Only the whole-file checksum notices, and that checksum is itself a row value.
Binding the identifiers makes misplacement a decryption failure at the chunk. X-TSK-010 brings the
four existing ciphers up to the same standard.

## Consequences

Positive:
- `INV-PAY-02` and `INV-RAIL-03` hold for the platform's first file inflow at the surface, and do
  not depend on what counterparties choose to put in their formats.
- The single-insider fabrication path is closed at two ranks, the domain and the `CHECK`, and
  unattested evidence is retained but inert.
- A stored file is always whole and verifiable. Tampering and misplacement are detected per chunk
  and per file, and the bytes never exist without their metadata.
- The storage decision stays reversible at the port, with measurable triggers in place of
  "operationally material".
- Every content access is attributable to a person and a reason.

Negative:
- A false positive refuses a genuine file until the counterparty re-issues it or a new format
  version ships. Meanwhile its expectations age into `MISSING_EXTERNAL` breaks: visible, not
  silent.
- Every upload needs two people, and an unattested upload waits. A pull of the same bytes does
  not release it.
- The content of a refused delivery is not held. Investigating exactly what it contained depends
  on the counterparty.
- A shape the screen misses rests encrypted and cannot be removed in Phase 8. Nothing is deleted
  (`INV-HIST-01`), and the PII-deletion tension is ADR-0036's, owned by Phase 15. The field
  classes, the golden files and the needle are the mitigation.
- A byte-identical re-issue of a wrongly declined upload meets the content address (point 8;
  recorded for P8-TSK-022).
- The bytes grow the database and its backups. The triggers must be watched, and
  `PHASE_8_PLAN.md`'s risk register carries them.

Operational impact: `finapp.settlement.file.received` (by `source` and outcome, new or
duplicate), `finapp.settlement.delivery.refused` (alert), `finapp.settlement.file.rejected`,
`finapp.settlement.file.pending` (including files awaiting attestation),
`finapp.settlement.file.age` (alert), `finapp.settlement.source.silence` (alert) and
`finapp.settlement.pull.failure`: counts, ages and verdicts only (ADR-0072). A refusal is worked
from its metadata row (reason, line, field): the counterparty is asked to re-issue, or a format
version is shipped.
Security impact: `FINAPP_SETTLEMENT_FILE_KEY` and the four report credentials take
`ConfinedCredentialVariablesTest` from eleven to sixteen. `SETTLEMENT_INGEST` and
`RECONCILIATION_INVESTIGATE` are held by `RECONCILIATION_OPERATOR` (identity `V017`), and
readmission's `RECONCILIATION_ADMINISTER` by `RECONCILIATION_CONTROLLER` (identity `V018`); the
two roles are pairwise disjoint (O1). Upload attestation joins `INV-AUD-04`'s subjects, and so
does the attestation of an unattested original's readmission. File content is classified
`RESTRICTED-PII`, and every read of it is audited. Every pull source's URL joins
`ProviderTransportGuard`'s list, so no report is pulled over plain transport off loopback.
Financial impact: this ADR posts nothing itself. It decides what may drive the postings ADR-0065
describes: only whole, authenticated, admitted evidence. A refused or rejected file moves no
money, and its expectations age instead of being assumed settled (`INV-SET-02`).

## Invariants / Constraints

`INV-SET-07` (new: evidence takes effect only whole and authenticated), `INV-REC-10` (new:
screened, encrypted with bound associated data, every content access audited), `INV-HIST-02`
(the precedence ruling in its note), `INV-PAY-02` and `INV-RAIL-03` (Verify extended to
settlement), `INV-REC-01`, `INV-HIST-01`, `INV-AUD-01`, `INV-AUD-03`, `INV-AUD-04` (upload
attestation), `INV-IDEM-01`, `INV-IDEM-03`, and `INV-KYC-06` and `INV-DSP-03` (the regime
reused). ADR-0036 (re-assessed), ADR-0020 (confined credentials), ADR-0022 (classification at the
ceiling), ADR-0046 (no connection across a pull), ADR-0008 (the collector SPI).

## Follow-up

- M8.1: `P8-TSK-002` (the source register, `settlement` `V002`, the encrypted file store, its
  cipher and key, the door screen with its byte-level fallback, `refused_delivery`) and
  `P8-TSK-003` (the upload door, attestation, audited content reads, the two permissions and
  `RECONCILIATION_OPERATOR`).
- `P8-TSK-008` (the `SettlementFormat` SPI and `SIM_PSP_CSV` v1's field-class screen; a 15-digit
  network transaction id is not refused; the decline, `RECEIVED | PARSED → REJECTED(DECLINED)`,
  with the parsed totals an attester reads first), `P8-TSK-009` (acceptance requires attestation
  for uploads), `P8-TSK-016` (the statement format; the needle in its free text).
  *(The decline was listed under `P8-TSK-003` until the Phase 7 → 8 transition's consistency
  review, B4. It needs the `REJECTED` state, which arrives with `P8-TSK-008`'s settlement
  `V003`, so the backlog moved it there.)*
- `P8-TSK-016` — **implemented** (2026-09-30): `SIM_STATEMENT_TAGGED` v1, MT940-shaped
  (`:20:`, `:25:`, `:28C:`, `:60F:`, `:61:`, `:86:`, `:62F:`), frozen by its golden file and
  confined to its adapter (`SettlementVocabularyIsConfinedTest`, now per adapter). §3's field-class
  screen holds field by field; so that an account identifier put where a reference belongs meets
  the free-text screen, the statement and remittance reference classes themselves exclude the
  international account shape and card-length digit runs — an IBAN in `:25:`, `:20:`, a line's
  reference or the `:86:` narrative is refused at the door, metadata only (§7's needle,
  `BankStatementCashDatabaseTest#theIbanNeedleReachesNoSink`). Decided here, as the backlog left
  it: a well-shaped `:25:` that is not the reference configured for the statement's currency
  rejects the file whole `MALFORMED` with the field `accountRef` — among §9's codes, the field
  failing its one admitted value; no new code — the value never echoed. The configured references
  (`finapp.settlement.bank.account-reference.*`) are construction-time configuration of a pure
  adapter, classified `CONFIDENTIAL`. §5's "a statement sequence already accepted" is enforced
  for any LIVE statement (`PARSED` or `ACCEPTED`): the parse's pre-check and settlement `V005`'s
  partial unique answer `CONFLICTING_BATCH`, retained. The statement's continuity facts ride out
  of the parse (`ParsedBatch.StatementFacts`) and are frozen with the parse statement.
- `P8-TSK-017` — **implemented** (2026-09-30): `SIM_SCHEME_JSON` v1, one JSON object per cycle
  report (amounts JSON strings, never numbers — a number invites a binary float), frozen by its
  golden file, its entry codes `CT` and `RT` confined to the adapter. §3 over a structured format:
  every DECODED string and every number token is held to its member's class and, failing it,
  screened as free text; an undeclared member's name is free text too; bytes that are not one
  well-formed object fall back to the conservative stream; the screen's record count is the
  entries', never the physical lines (a minified report is one line). No class admits an
  instrument shape — with one exact exception, decided here from a delegated build's find: the
  end-to-end and our-reference classes also admit the platform's OWN minted reference, a dashless
  UUIDv7, whose hex holds a card-length digit run about once in sixty (the acquirer reference's
  precedent: a class admitting its own legitimate digit runs); without it the platform's own
  references would have been refused at random. A file declaring several cycles is
  `UNSUPPORTED_FORMAT`. Recorded limits: a scheme reference is bounded at the canonical line's 100
  characters though payments admits 128, and the cycle token at the reference alphabet though
  payments admits any 1..64 characters, with no digit run of card length even across single
  dashes (`CYC-2026-09-26-01`, the simulated scheme's shape, joins ten digits; a date running
  into a five-digit sequence would join thirteen and be refused — found by the gate as a flaky
  fixture, the fixture corrected, never the class) — a scheme exceeding any of these needs v2.
- `P8-TSK-018` — **implemented** (2026-09-30): `SIM_PAYOUT_CSV` v1, the PSP report's sibling
  (`H`/`D`/`T` records, signed amounts, the gross-plus-fee split naming the payout by
  `ORIGINAL_REF`), built by a delegated agent, golden-filed with pinned fingerprints, its codes
  `SETTLED` and `RETURNED` confined. §3 field by field: the beneficiary name is the one declared
  free-text field, always screened (a card number or an account identifier refused at the door,
  metadata only — `INV-RAIL-03`); the provider reference is held to the no-instrument class; our
  reference admits the platform's OWN minted `pyo-` UUIDv7 EXACTLY (its dash-joined hex reaches a
  card-length run about once in several dozen — `P8-TSK-017`'s rule, dashed form). Differences from
  the PSP adapter, each deliberate: amounts at twelve integer digits, the header's name and version
  screened by class, a well-formed code required before an unknown one maps to `OTHER_IN`/`OTHER_OUT`,
  and each record's defects gathered apart — the PSP adapter's defect-cap crash, recorded as debt,
  does not recur here. Recorded limits: references bounded at the canonical line's 100 characters
  though merchant admits 128; no quoting, so a comma in the beneficiary rejects the file whole.
- M8.7: `P8-TSK-021` (pull acquisition, the four credentials, `pull_permit`, source silence, and
  `ProviderTransportGuard` extended to every pull source's URL (point 1); the first deferral
  candidate) and `P8-TSK-022` (readmission, including the attested readmission of an unattested
  original and the readmission of a `CONFLICTING_BATCH` original whose conflicting batch is
  `REPUDIATED`; the re-parse verification; and the declined-upload question of point 8).
- `P8-TST-001` delivers every file twice, by upload with attestation and by racing pulls, out of
  order and late.
- `X-TSK-010`: bind associated data in `EvidenceCipher`, `PayoutEvidenceCipher`, `DocumentCipher`
  and `SecretCipher`.
- ADR-0036 is annotated at the transition as re-assessed by this ADR for settlement files. The
  drift pass also covers `DATA_ARCHITECTURE.md`:12, which lists settlement files under object
  storage as the target stack; it stays true as a target once a trigger fires.
- **What is implemented.** `P8-TSK-002` (2026-09-29) delivered the store and the screen halves
  of this decision: `FileReception.receive` in its fixed order behind the conservative
  whole-stream screen (point 3's fallback; the field-class screens arrive with each format),
  the refusal as metadata plus `settlement.SettlementDeliveryRefused` (point 4, the bounds
  included), the content-address convergence with its receipts (point 5), and the encrypted
  store behind `SettlementFileStore` — 1 MiB AES-256-GCM chunks under
  `FINAPP_SETTLEMENT_FILE_KEY` with the associated data of point 6 bound and the
  whole-plaintext checksum verified on every read. `P8-TSK-003` (2026-09-29) opened the first
  channel: point 1's `UPLOAD` row (`POST /v1/operator/settlement/files`, `202` with
  `duplicateOf`, keyed per principal under `settlement.upload:<actorType>:<actorId>`, the
  claim and the reception one transaction), point 2's attestation (`POST
  .../files/{id}/attestation` under `SETTLEMENT_INGEST`; the row lock, the conditional
  `NULL → value`, the distinctness refused at the domain and by the `CHECK`; the decline
  moved to `P8-TSK-008` at elaboration, where `REJECTED` exists), and point 7's one read path
  (`POST .../files/{id}/content-reads {reason}` under `RECONCILIATION_INVESTIGATE`, one
  `settlement.SettlementFileContentRead` record per read, a verification failure audited
  `FAILED` and serving nothing) with the metadata reads beside it, plus the
  `finapp.settlement.file.pending`/`.age` gauges of point 2's "visibly". Pull is
  `P8-TSK-021`'s, readmission `P8-TSK-022`'s. `P8-TSK-008` (2026-09-29) delivered the format
  and parse halves: the `SettlementFormat` SPI with `SIM_PSP_CSV` v1 frozen by its golden
  file (point 8's version discipline), point 3's field-class screen filling the door's seam —
  reference fields by shape, a Luhn-valid network transaction id never tested as free text, a
  field failing its declared class screened as free text (C6) — the parse leg of
  `SettlementIntakeSchedule` under point 9 whole (one transaction per file; up to 100
  `ingestion_error` rows; `settlement.SettlementFileRejected` audited acting-only and
  published through the outbox; our exception leaving the file `RECEIVED` with
  `parse_failures + 1` and the back-off), settlement `V003`'s batch, lines, typed references
  and folded totals with the live-batch unique written whole, `CONFLICTING_BATCH` for a
  second declaration (retained, point 8's readmission path), and point 4's moved decline
  (`RECEIVED | PARSED → REJECTED(DECLINED)`, the batch rejected in the same transaction and
  the live key freed). The event's payload carries `sourceId` rather than the drafted
  `sourceCode`: `EventPayload`'s vocabulary is identifiers and enumerated names
  (`INV-AUD-02`), and a dotted source code is neither. `P8-TSK-009` (2026-09-29) delivered point 9's acceptance half and point 2's eligibility: an upload moves money only past its second person (the claim query's predicate, the domain's re-read, `V002`'s `CHECK`s), a pull by its channel; the gapless `source_sequence` under the source row's lock with `UNIQUE (source_id, source_sequence)` behind it; `SOURCE_RETIRED` rejecting RETAINED under that same lock; and settlement `V004` completing the file machine.
  Everything else here is the decided design, corrected by the tasks that build it.
- The Phase 8 review reads this ADR against the code before accepting it (`P8-DOC-001`, the
  `P7-DOC-001` precedent).
