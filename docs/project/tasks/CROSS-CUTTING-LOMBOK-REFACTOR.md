# X-TSK-001 — Lombok adoption and the Phase 1–6 refactor

**Status:** `PLANNED`. Batch 0 (the build and the standard) was applied on 2026-09-23. Batches 1–9
wait for the owner's go-ahead. **No application source file has been changed.**
**Decision:** [ADR-0055](../../adr/ADR-0055-lombok-compile-time-boilerplate.md) (`Proposed`).
**Rule:** [`.claude/rules/java-lombok.md`](../../../.claude/rules/java-lombok.md).
**Backlog:** `X-TSK-001` in [`BACKLOG.md`](../BACKLOG.md) §Cross-cutting work.

This is a **cross-cutting refactor**. It adds no feature, changes no business behaviour, leaves the
roadmap alone, and does not displace the current task (`P6-TSK-011`). It is also not a Phase 6
item: an `X-` task gates no phase exit.

---

## 1. Objective

Make Lombok the project standard for repetitive Java boilerplate. Then convert the existing Phase
1–6 code **where, and only where, the conversion can be proved behaviour-identical**. Proved here
means the compiled bytecode's constructors, fields, null checks and methods are shown to be
unchanged, not argued to be.

## 2. Scope

- **Batch 0 (applied):** the build configuration, `lombok.config`, the rule, the ADR and the
  documentation.
- **Batches 1–9:** 152 production classes, converted module by module. The conversions are:
  - 22 hand-declared SLF4J loggers → `@Slf4j`;
  - 104 constructors that only assign their final fields → `@RequiredArgsConstructor`, with
    `@NonNull` on every field the old constructor null-checked;
  - 28 contract enums whose constructors only assign → `@RequiredArgsConstructor`.

  Two classes gain both a logger and a constructor conversion.

## 3. Out of scope

- **Anything the audit classified DO NOT REFACTOR** (446 production classes, §8 and appendix C).
  That includes every aggregate, value object, secret type, cipher, record, exception and `Money`.
- **Records.** A record is Java's own `@Value`, and this codebase models its 93 production values
  that way. Converting them to Lombok would be a regression.
- **Accessors.** The codebase uses fluent, record-style accessors (`id()`, `status()`): 248 trivial
  ones across 60 production classes. `@Getter` would rename them `getId()`, changing the API. The
  only way to keep the names is `@Accessors(fluent = true)`, which is experimental and banned by
  `lombok.config`.
- **New Lombok surface.** No `@Builder`, `@Getter`, `@Setter`, `@Value`, `@ToString` or
  `@EqualsAndHashCode` is introduced by this refactor. New code may use them under the rule.
- **Test code** (354 test and 7 test-fixture files). It is left as written. Lombok is available
  there for new code.
- **Fixing the pre-existing issues in §19.** They have their own tasks; this plan only refuses to
  hide them.

## 4. What the audit found about the codebase

All 1,052 Java files were read: **691 production**, 354 test, 7 test-fixture.

| Finding | Consequence for Lombok |
|---|---|
| **No ORM.** Persistence is plain JDBC (ADR-0033), and `NoObjectRelationalMapperTest` forbids an ORM | JPA risks (no-argument constructors, lazy proxies, entity equality) have no subject. Aggregates rehydrate through static factories, which stay |
| **94 records** (93 production) | DTOs, events, value objects and commands already carry no boilerplate |
| **Fluent accessors**: 248 trivial accessor methods in 60 production classes; 3 `getX()` getters, all in tests | `@Getter` does not fit the accessor convention (§3) |
| **No setters. No builders.** | Nothing to convert. State changes are named domain methods and conditional transitions |
| **21 hand-written `equals`/`hashCode`, 39 hand-written `toString`** | Identity equality for aggregates, scale-aware equality for `Money`, redaction for secrets. All stay |
| **22 loggers**, all `private static final`, each `getLogger(ItsOwnClass.class)`; 3 named `LOGGER` | `@Slf4j` produces the same logger (same name, same type); the three are renamed to `log` |
| **137 assign-only constructors** (136 found by the parser, plus `AuthenticationController`, read by hand after a multi-line `@RequestMapping` defeated it). 104 are exactly what `@RequiredArgsConstructor` generates. 20 are private constructors behind factories (18 production, 2 test fixtures). 12 production constructors rename a parameter or have a non-final field. 1 is in test code | 104 candidates, split SAFE or REQUIRES CAREFUL REVIEW (§8). The 12 keep their constructors; `PaymentWebhookService` still gets `@Slf4j` |
| **31 enums with assign-only constructors** | 28 production contract enums are candidates. `RoundingPolicy` stays (money kernel); `TestTier` and `ProbeAuditAction` are test code |
| **81 exceptions**, 1 Jackson-annotated type (`ProblemDetailBody`, a record), 4 `Serializable` types | None converted |
| **Spring**: 32 `@RestController`, 23 `@Configuration`, 6 `@Component`, 5 `@Service`, 1 `@RestControllerAdvice`; no `@Transactional` proxies (transactions are programmatic) | Constructor injection keeps the single constructor Spring autowires |

## 5. Modules affected

| Module | Production files | SAFE | CAREFUL | DO NOT | N/A | Test files | Batch |
|---|---|---|---|---|---|---|---|
| `accounts` | 17 | 0 | 4 | 10 | 3 | 3 | 3 |
| `app` | 188 | 51 | 19 | 113 | 5 | 205 | 8 + 9 |
| `checkout` | 18 | 1 | 2 | 11 | 4 | 4 | 5 |
| `consent` | 13 | 0 | 3 | 8 | 2 | 3 | 7 |
| `identity` | 77 | 0 | 21 | 47 | 9 | 17 | 6 |
| `kyc` | 57 | 1 | 7 | 39 | 10 | 18 | 7 |
| `ledger` | 70 | 0 | 9 | 49 | 12 | 15 | 2 |
| `merchant` | 47 | 0 | 8 | 33 | 6 | 11 | 5 |
| `party` | 16 | 0 | 4 | 10 | 2 | 4 | 7 |
| `paymentmethods` | 14 | 0 | 2 | 9 | 3 | 6 | 4 |
| `payments` | 58 | 1 | 9 | 38 | 10 | 11 | 4 |
| `platform` | 64 | 0 | 5 | 43 | 16 | 46 | 1 |
| `sharedkernel` | 22 | 0 | 0 | 17 | 5 | 11 | none |
| `transfers` | 30 | 0 | 5 | 19 | 6 | 7 | 3 |
| **Total** | **691** | **54** | **98** | **446** | **93** | **361** | |

`sharedkernel` is untouched: it holds `Money`, currency and rounding, and every candidate there is a
private-constructor value type.

## 6. Build changes (Batch 0 — applied, verified)

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | `lombok = "1.18.46"`, exactly what Spring Boot 4.1.1 manages (checked in the BOM). Pinned in the catalog like JUnit and AssertJ, because `sharedkernel` applies no Spring BOM. Library `lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }` |
| `build-logic/.../finapp.java-conventions.gradle.kts` | `compileOnly`, `annotationProcessor`, `testCompileOnly` and `testAnnotationProcessor`, plus `testFixtures*` where `java-test-fixtures` is applied. Wired once, from the catalog, for every module. `lombok.config` declared as a `JavaCompile` input |
| `lombok.config` (new, root) | `stopBubbling`; `@lombok.Generated` on generated members; no `@ConstructorProperties`; `@NonNull` throws `NullPointerException`; generated `toString` is opt-in per field. Compile errors for `@Data`, `@SneakyThrows`, `@Synchronized`, `val`/`var`, `@Cleanup`, experimental features, `onX` and every non-SLF4J logger. All 22 keys confirmed present in Lombok 1.18.46 |
| 14 × `gradle.lockfile` | One line each. Lombok appears in `annotationProcessor`, `compileClasspath`, `testAnnotationProcessor` and `testCompileClasspath` (and the two fixture configurations in `identity` and `platform`). It appears in **no runtime and no SBOM configuration** |
| `gradle/verification-metadata.xml` | Exactly two artefacts: `lombok-1.18.46.jar` and `.pom`. Lombok's POM declares no parent and no dependencies, so a cold-cache resolution needs nothing more (the README §7a concern) |

**Evidence**:
- The build compiles cleanly under `-Xlint:all -Werror`.
- The `app` boot jar holds 100 libraries and no Lombok.
- `@NonNull` and `@Generated` have `CLASS` retention. They are stored as `RuntimeInvisible*`
  attributes, so nothing at run time can see or need Lombok.
- After the trial conversion was withdrawn, the restored sources compiled, with Lombok on the
  processor path, to bytecode identical to the pre-refactor baseline for all 123 trial classes.

An unrelated `build-logic` lock drift that the regeneration produced (Kotlin tooling 2.4.20-RC3 →
2.4.20) was **backed out**, not carried in.

## 7. Lombok strategy

| Annotation | In this refactor | Why |
|---|---|---|
| `@Slf4j` | 22 loggers | Same logger name and type; the declaration disappears |
| `@RequiredArgsConstructor` (+ `@NonNull`, `AccessLevel.PACKAGE`) | 104 classes, 28 enums | Only where the existing constructor is exactly what Lombok generates |
| `@Getter` / `@Setter` | None | Fluent-accessor convention (§3). Setters would bypass domain methods such as `authorize()`, `capture()`, `refund()`, `revoke()`, `activate()` and `suspend()` |
| `@Builder` | None | No hand-written builder exists to replace. A builder is new API surface, not removed boilerplate |
| `@Value` | None | Records already |
| `@EqualsAndHashCode` / `@ToString` | None | Every hand-written one carries identity, scale or redaction semantics |
| `@NoArgsConstructor` / `@AllArgsConstructor` | None | No framework needs them: no ORM, and Jackson binds records. `force = true` is never used |
| `@Data`, `@SneakyThrows`, `@Synchronized`, `val`/`var`, `@Cleanup`, experimental features | Compile errors | `lombok.config` |

**The null-check contract.**
- **102 of the 104 class constructors** null-check every dependency with `Objects.requireNonNull`.
  Each of those fields gets `@NonNull`, so the same `NullPointerException` is thrown at the same
  moment. Only the message text changes, from `x must not be null` to
  `x is marked non-null but is null`, and no test asserts the old text (checked).
- **The other two (`CorrelationFilter`, `ProblemDetailWriter`) and all 28 enum constructors** check
  nothing today, so they get no `@NonNull`. Adding a check would itself be a behaviour change.

## 8. Candidates

- **SAFE TO REFACTOR — 54 classes.** The conversion is mechanical and provable by bytecode, with no
  documentation to move and no same-typed dependency pair. These are the `app` web, telemetry,
  scheduling and composition classes, plus three logger-only swaps in `payments`, `checkout` and
  `kyc`.
- **REQUIRES CAREFUL REVIEW — 98 classes.** Still provable by bytecode, but each needs a named
  judgement:
  - a financial, payment, identity/security, KYC/KYB, consent or PII bounded context, reviewed in
    its own module batch;
  - constructor documentation to move onto fields (6);
  - same-typed dependencies, which make field order load-bearing;
  - package-private access;
  - a `@Configuration` class (CGLIB-proxied);
  - a kernel module;
  - a contract enum.
- **DO NOT REFACTOR — 446 classes**, each with a stated reason (appendix C).

The full per-class lists are in the appendix.

## 9. Semantic safety

What each conversion must leave unchanged, and how that is shown:

| Property | `@Slf4j` | `@RequiredArgsConstructor` (+ `@NonNull`) | Shown by |
|---|---|---|---|
| Constructor semantics | Unchanged: no constructor involved | Same access, descriptor and parameter order; each parameter feeds the same field | Bytecode gate (§15) |
| Nullability | Unchanged | `requireNonNull` → generated `NullPointerException`, same count per constructor | Bytecode gate: null-check count equality |
| Mutability | Unchanged | Fields stay `private final`, so JMM final-field publication is unchanged | Bytecode gate: field flags equality |
| Equality / hashCode | None generated | None generated | `lombok.config`; the diff contains no `@EqualsAndHashCode`, `@Value` or `@Data` |
| `toString` | None generated | None generated | As above; generated `toString` is opt-in per field anyway |
| Serialization / deserialization / JSON | No serialized type is touched | No serialized type is touched: DTOs and events are records, and `ProblemDetailBody` is a record | Appendix; `OpenApiContractTest`; end-to-end response assertions |
| ORM behaviour | N/A: no ORM | N/A | `NoObjectRelationalMapperTest` |
| Proxy behaviour | Unchanged | Spring autowires the one constructor, whose signature is unchanged. CGLIB subclasses `@Configuration` classes through the same constructor | Context-starting tests; bytecode gate |
| API behaviour | Unchanged | No public method changes | Bytecode gate: method-set equality |
| Validation | Unchanged | Bean Validation lives on request records, which are untouched | Slice and database suites |
| Domain invariants | Unchanged | Factories, private constructors and transition methods are untouched | Appendix C; bytecode gate |
| Security behaviour | Log statements are not touched | No secret or credential type is touched | Security suites (§15) |
| Financial behaviour | Unchanged | No posting, hold, reversal, balance or fee path changes; method bodies are byte-identical | Bytecode gate; ledger and payment suites |
| Static initialisation | The logger's three instructions may move **earlier** only | Unchanged | Bytecode gate |

## 10. Financial domain review

- **Untouched:** `Money`, `CurrencyCode`, `RoundingPolicy` and the whole of `sharedkernel`. Also
  every journal type: `JournalEntry` (private constructor), `JournalLine` (record), `LedgerAccount`
  (identity equality). Also `Hold` (record); `PostingService`, `HoldService` and `ReversalService`
  (constructors with logic); `PaymentIntent`, `PaymentAttempt` and `Refund` (fluent accessors, no
  setters); the fee types `FeeRate`, `FeeScheduleVersion`, `PaymentFeePin` and `FeeAssessment`;
  and `PaymentService` and `CheckoutService`, whose constructors differ from what Lombok generates.
- **Converted, constructors only:** services that sit beside the money paths, such as
  `PostingEffect`, `ChartOfAccounts`, `AvailableBalance`, the payment commands,
  `MerchantSettlement` and `MerchantPayable`. Their method bodies — the posting composition, the
  hold arithmetic, the fee allocation and the reservation — are proved byte-identical, so no
  double-entry, precision, currency, posting, reversal, balance or idempotency behaviour can move.
- Lombok is never used merely to cut line count where it would hide financial behaviour. The
  enum constructors of error codes and audit actions carry codes that are contracts, which is why
  they are REQUIRES CAREFUL REVIEW despite being mechanical.

## 11. Distributed-system review

Ten or more instances of every service see identical bytecode, so they behave identically, and a
rolling deploy that mixes old and new instances is indistinguishable at run time. The conversions
introduce:

- **no mutable shared state.** The logger is a `static final` immutable reference, as before, and
  `@Setter` is not used.
- **no hidden synchronisation.** `@Synchronized` is a compile error, and
  `NoSingleInstanceAssumptionRulesTest` still refuses `synchronized` blocks, which the ASM check
  would see in generated code too.
- **no process-local state and no change to thread-safety.** Fields stay `final`.
- **no change to transaction, idempotency, outbox, inbox or event contracts.** `TransactionTemplate`
  usage, envelopes and payload types are untouched. The platform batch runs the Kafka tier because
  it touches the relay and receiver loggers.

## 12. API and serialization review

- **JSON:** every request, response and view type is a record, and none is touched. The field
  names, null handling, constructors and deserialization Jackson sees cannot change.
- **Error codes:** the 28 contract enums keep their constants, codes, statuses, titles and
  ordinals; only the constructor declaration disappears, and the bytecode gate proves it. The
  error-contract and OpenAPI tests run in every batch.
- **Events:** `EventEnvelope`, `EventPayload` and the schema versions are untouched.
- **Java serialization:** the four `Serializable` types (`CurrencyCode`, `IdempotencyKey`,
  `InboxKey`, `KycPolicyVersion`) are DO NOT REFACTOR.

## 13. Persistence review

- **No ORM exists** (ADR-0033; `NoObjectRelationalMapperTest`). No no-argument constructor is
  added and `@NoArgsConstructor(force = true)` is never used.
- The JDBC stores converted here (`JdbcCheckStore`, `JdbcDocumentStore`, `JdbcJournalEntryStore`,
  `JdbcStatementDerivation`, `JdbcProviderEvidenceStore`, `JdbcRecoveryRequestStore`,
  `JdbcRoleAssignmentStore`) lose only their constructors. SQL, row mapping and SQLState
  translation are byte-identical.
- Aggregates' `rehydrate` factories, identity equality and immutable fields are untouched.
- Each batch runs the database suites of its module. No migration and no schema change is
  involved.

## 14. Batches

Module by module, smallest and most fundamental first, so every later batch runs against
already-converted lower modules. One commit per batch.

| Batch | Scope | Classes | Specifics | Integration suites to run |
|---|---|---|---|---|
| **0** | Build, `lombok.config`, rule, ADR, docs | — | **Applied** | Clean compile; hermetic; the boot jar check |
| **1** | `platform` | 5 | Loggers in `InboxConsumer`, `KafkaEventReceiver` and `OutboxRelay` (which has two constructors, so logger only); `PlatformAuditAction` and `PlatformErrorCode` enums | `platform` database suites; **kafka tier** |
| **2** | `ledger` | 9 | `PostingEffect` (package-private); constructor docs to move in `JdbcJournalEntryStore` and `ProjectionVerification`; `AccountPurpose`, `LedgerAuditAction` and `LedgerErrorCode` | Ledger database suites: posting, holds, reversal, adjustment, projection, statement, trial balance |
| **3** | `accounts`, `transfers` | 9 | `TransferExecution`'s comment on its required controls moves onto the fields | Account and transfer database suites |
| **4** | `payments`, `paymentmethods` | 12 | Payment commands and outcomes; `PaymentSweeper` logger; evidence store | Payment database suites (the wallet refund included) |
| **5** | `merchant`, `checkout` | 11 | `MerchantApiKeys` (issues secrets: constructor only); `CheckoutExpirySweeper` logger | Merchant, checkout and payment-flow database suites |
| **6** | `identity` | 21 | Security-critical. `CredentialVerifier` logger rename; `SessionIssue` and `AuthenticationThrottle` notes move onto fields | Identity, session, MFA, recovery and credential database suites; `MfaBypassPathsAreEnumeratedTest` |
| **7** | `party`, `kyc`, `consent` | 15 | PII contexts. `DocumentAccess` (audited document reads); `CustomerOpenedOpensCase` logger | Party, KYC/KYB, document and consent database suites |
| **8** | `app`, security-sensitive web layer | 19 | `AuthenticationController` and `RegistrationController` (package-private); `SessionAuthenticationInterceptor` (logger rename + constructor); `MerchantKeyAuthenticationInterceptor`; credential, MFA, recovery and session controllers; merchant API-key surface; payment webhook; checkout session wiring (`CheckoutPaymentParticipants` docs); `ApiVersionConfiguration` (`@Configuration`) | Authentication and session suites; merchant key suites; webhook suites; checkout flow |
| **9** | `app`, remaining | 51 | Controllers, telemetry loggers, filters, schedules, composition adapters, query services | All `app` database suites (the composition root touches every flow) |

## 15. Verification after each batch — the gate

1. **Baseline.** On a clean compile of the batch's base commit, record a `javap -p -c -constants`
   fingerprint of every class in the batch:
   - each constructor's access and descriptor, and which parameter slot feeds which field;
   - the count of `Objects.requireNonNull` calls;
   - the field and method signatures;
   - the static initialiser.
2. **Convert** (`@Slf4j`, `@RequiredArgsConstructor`, `@NonNull`, `AccessLevel.PACKAGE`). Move any
   constructor documentation onto its fields, and change nothing else.
3. **Compilation:** `./gradlew clean compileJava compileTestJava compileTestFixturesJava` under
   `-Werror`, with zero warnings.
4. **Bytecode equivalence**, against the baseline, for every class in the batch:
   - identical constructor access, descriptor and parameter-to-field mapping;
   - generated `NullPointerException` throws = baseline `requireNonNull` count;
   - identical fields, where a logger may only become `private static final Logger log`;
   - identical methods;
   - an identical static initialiser, except that the logger's three instructions may move
     earlier.

   Any difference fails the batch.
5. **Diff audit:** only the four allowed annotations appear, and no DO NOT REFACTOR class is
   touched. No comment or Javadoc is lost: every removed comment line must reappear on a field.
6. **Unit and architecture:** `./gradlew test --rerun`, fleet-wide, counted from fresh result
   files. That includes the module boundary rules, `sharedkernel` framework-freedom,
   `NoSingleInstanceAssumptionRulesTest`, `OwnershipIsScopedTest`, the ADR register, the planned
   meters and `OpenApiContractTest` (API contract).
7. **Security:** `NoUnwrappedSecretRulesTest`, `SecretsAreUnwrappedInOnePlaceTest`,
   `CredentialReachesNoEmittedSinkTest`, `CredentialNeverReachesALogTest`, `LogRedactionTest` and
   `MfaBypassPathsAreEnumeratedTest`, all in the hermetic run.
8. **Integration, persistence and serialization:** the batch's database suites (§14). Batch 1 also
   runs the Kafka tier. Batches 4, 5 and 9 include the end-to-end response-body suites.
9. **Commit** the batch with its class list and the gate's evidence. It stays revertible on its own.

## 16. Testing strategy

- No test is changed by the refactor. A test that needs changing is evidence the conversion was
  not behaviour-identical, and the batch stops.
- Evidence comes from fresh runs only, never from stale result files. Gradle's up-to-date checks
  are defeated with `--rerun`, because a skipped module's old XML is not a result.
- The final acceptance (§18) runs the full hermetic, database and Kafka tiers once, since the
  refactor as a whole spans every module.

## 17. Rollback

- Every batch is one commit. `git revert` of that commit restores the batch exactly. There is no
  data, schema, migration or deployment-ordering consequence, because Lombok is compile-time only
  and the bytecode is identical.
- **Batch 0**'s revert removes the catalog entry, the convention-plugin block, `lombok.config`, the
  14 lockfile lines and the two checksum entries together. It is only possible after every later
  batch has been reverted, since converted classes need Lombok to compile.
- A rolling deploy may mix pre- and post-batch instances freely, because their behaviour is
  identical.

## 18. Acceptance criteria

1. All 152 candidates are converted in Batches 1–9, each batch passing the §15 gate.
2. No DO NOT REFACTOR class changed; `git diff` over the batches touches only the appendix A and B
   lists.
3. No annotation outside `@Slf4j`, `@RequiredArgsConstructor`, `@NonNull` and `AccessLevel`.
   No generated `toString`, `equals`, `hashCode`, getter, setter or builder.
4. A clean compile of every source set under `-Werror`.
5. The hermetic, database and Kafka tiers are green from fresh runs. The pre-existing failure in
   §19 is fixed first by its own task, or explicitly carried with that task named.
6. Lombok is compile-time only: the lockfiles show it in no runtime or SBOM configuration, and the
   boot jar contains none.
7. No test was changed to make a batch pass.
8. The rule, ADR-0055 and this plan agree with what was done, and the change log carries one row
   per batch.

## 19. Pre-existing issues — not fixed here, and not to be masked

- **`OperationalChartDatabaseTest#everyCombinationResolves` fails on untouched `HEAD`.** It has
  been broken since `P6-TSK-003` added the merchant-owned `MERCHANT_PAYABLE` purpose: the test
  skips only customer-owned purposes. The full database tier found it; Phase 6 task work ran only
  targeted suites. **It blocks acceptance criterion 5 and the Phase 6 exit battery.** Recorded in
  `CURRENT_STATE.md` §Blockers.
- **`SimulatedTokenisationAdapterTest#aTimeoutIsUnavailable` is flaky**, failing about one run in
  three with no change to its module. A batch that meets it re-runs it and says so, rather than
  counting it as a regression.

## 20. Evidence from the trial conversion

A one-shot conversion of the 123 original constructor and logger candidates was performed on
2026-09-23. It was then withdrawn, so that the refactor proceeds in controlled batches as this
plan requires. It is the evidence this plan's method works on this codebase:

- **0 bytecode differences in 123 classes.** Every constructor had the same access, signature and
  parameter-to-field mapping, and there were **356 null checks before and 356 after**.
- 1,457 hermetic tests, 952 database tests and 14 Kafka tests ran, with one failure: the
  pre-existing one in §19.
- About 800 lines removed.
- The patch was kept outside the repository; the batches re-derive it module by module.

The 28 enums and `AuthenticationController` were added to the candidate set after the trial, by
the complete audit. The batches that hold them prove them for the first time.

---

## Appendix — candidate lists

Generated from the audit of the source tree at `1d9f97d`. Batch numbers refer to §14.

### A. SAFE TO REFACTOR — 54 classes

| Batch | Module | Class | Conversion |
|---|---|---|---|
| 4 | `payments` | `PaymentSweeper` | `@Slf4j` |
| 5 | `checkout` | `CheckoutExpirySweeper` | `@Slf4j` |
| 7 | `kyc` | `CustomerOpenedOpensCase` | `@Slf4j` |
| 9 | `app` | `AccountController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `AccountService` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `AdjustmentController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `ApiErrorHandler` | `@Slf4j` (rename `LOGGER` → `log`) |
| 9 | `app` | `BeneficiaryController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `CheckoutConfirmationController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `CheckoutExpirySweeperSchedule` | `@Slf4j` |
| 9 | `app` | `ConsentController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `ContactChannelController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `CorrelationFilter` | `@Slf4j` + `@RequiredArgsConstructor` |
| 9 | `app` | `FeeScheduleController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `FeeScheduleOperations` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `IdentityAdministrationController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `IdentityMetrics` | `@Slf4j` |
| 9 | `app` | `InboxConsumers` | `@Slf4j` |
| 9 | `app` | `JdbcPaymentParticipants` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `JdbcTransferParticipants` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `KybController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `KycCaseController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `KycDocumentController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `KycMetrics` | `@Slf4j` |
| 9 | `app` | `LedgerAdjustments` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `LedgerMetrics` | `@Slf4j` |
| 9 | `app` | `MeController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantBoundCaptureComposition` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantBoundRefundComposition` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantFeeScheduleController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantOperations` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantOperationsController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantPayableController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantPayableQuery` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantSelfController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantSelfView` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantTransactionController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MerchantTransactionReport` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `MeteredPaymentProvider` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `OrganisationController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `OutboxMetrics` | `@Slf4j` |
| 9 | `app` | `OutboxRelaySchedule` | `@Slf4j` |
| 9 | `app` | `PaymentController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `PaymentMethodController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `PaymentMetrics` | `@Slf4j` |
| 9 | `app` | `PaymentSweeperSchedule` | `@Slf4j` |
| 9 | `app` | `ProblemDetailWriter` | `@RequiredArgsConstructor` |
| 9 | `app` | `ProviderCallbackController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `ProviderCallbackService` | `@Slf4j` |
| 9 | `app` | `RequestSizeLimitFilter` | `@Slf4j` |
| 9 | `app` | `ReviewController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `TransferController` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `VerifiedAccountHolder` | `@RequiredArgsConstructor` + `@NonNull` fields |
| 9 | `app` | `VerifiedMerchantOrganisation` | `@RequiredArgsConstructor` + `@NonNull` fields |

### B. REQUIRES CAREFUL REVIEW — 98 classes

| Batch | Module | Class | Conversion | Review points |
|---|---|---|---|---|
| 1 | `platform` | `InboxConsumer` | `@Slf4j` | kernel module: every other module depends on it |
| 1 | `platform` | `KafkaEventReceiver` | `@Slf4j` | kernel module: every other module depends on it |
| 1 | `platform` | `OutboxRelay` | `@Slf4j` | kernel module: every other module depends on it |
| 1 | `platform` | `PlatformAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 1 | `platform` | `PlatformErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 2 | `ledger` | `AccountPurpose` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 2 | `ledger` | `AvailableBalance` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (ledger) bounded context: reviewed in its own module batch |
| 2 | `ledger` | `ChartOfAccounts` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (ledger) bounded context: reviewed in its own module batch |
| 2 | `ledger` | `JdbcJournalEntryStore` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (ledger) bounded context: reviewed in its own module batch |
| 2 | `ledger` | `JdbcStatementDerivation` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (ledger) bounded context: reviewed in its own module batch |
| 2 | `ledger` | `LedgerAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 2 | `ledger` | `LedgerErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 2 | `ledger` | `PostingEffect` | `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` + `@NonNull` fields | package-private constructor: access = AccessLevel.PACKAGE; financial (ledger) bounded context: reviewed in its own module batch |
| 2 | `ledger` | `ProjectionVerification` | `@RequiredArgsConstructor` + `@NonNull` fields | constructor documentation to move onto fields; financial (ledger) bounded context: reviewed in its own module batch |
| 3 | `accounts` | `AccountClosing` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (accounts) bounded context: reviewed in its own module batch |
| 3 | `accounts` | `AccountOpening` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (accounts) bounded context: reviewed in its own module batch |
| 3 | `accounts` | `AccountsAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 3 | `accounts` | `AccountsErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 3 | `transfers` | `BeneficiaryCreation` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (transfers) bounded context: reviewed in its own module batch |
| 3 | `transfers` | `TransferExecution` | `@RequiredArgsConstructor` + `@NonNull` fields | constructor documentation to move onto fields; financial (transfers) bounded context: reviewed in its own module batch |
| 3 | `transfers` | `TransferReversal` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (transfers) bounded context: reviewed in its own module batch |
| 3 | `transfers` | `TransfersAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 3 | `transfers` | `TransfersErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 4 | `paymentmethods` | `PaymentmethodsAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 4 | `paymentmethods` | `PaymentmethodsErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 4 | `payments` | `JdbcProviderEvidenceStore` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; financial (payments) bounded context: reviewed in its own module batch |
| 4 | `payments` | `PaymentCancellation` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (payments) bounded context: reviewed in its own module batch |
| 4 | `payments` | `PaymentCapture` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (payments) bounded context: reviewed in its own module batch |
| 4 | `payments` | `PaymentConfirmation` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (payments) bounded context: reviewed in its own module batch |
| 4 | `payments` | `PaymentCreation` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (payments) bounded context: reviewed in its own module batch |
| 4 | `payments` | `PaymentOutcomes` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (payments) bounded context: reviewed in its own module batch |
| 4 | `payments` | `PaymentRefund` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (payments) bounded context: reviewed in its own module batch |
| 4 | `payments` | `PaymentsAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 4 | `payments` | `PaymentsErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 5 | `checkout` | `CheckoutAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 5 | `checkout` | `CheckoutErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 5 | `merchant` | `FeeSchedules` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (merchant fees and payable) bounded context: reviewed in its own module batch |
| 5 | `merchant` | `MerchantAdministration` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (merchant fees and payable) bounded context: reviewed in its own module batch |
| 5 | `merchant` | `MerchantApiKeys` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; financial (merchant fees and payable) bounded context: reviewed in its own module batch |
| 5 | `merchant` | `MerchantAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 5 | `merchant` | `MerchantErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 5 | `merchant` | `MerchantOnboarding` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (merchant fees and payable) bounded context: reviewed in its own module batch |
| 5 | `merchant` | `MerchantPayable` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (merchant fees and payable) bounded context: reviewed in its own module batch |
| 5 | `merchant` | `MerchantSettlement` | `@RequiredArgsConstructor` + `@NonNull` fields | financial (merchant fees and payable) bounded context: reviewed in its own module batch |
| 6 | `identity` | `AuthenticationThrottle` | `@RequiredArgsConstructor` + `@NonNull` fields | constructor documentation to move onto fields; security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `Authorization` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `ContactChannelService` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `CredentialAlgorithm` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 6 | `identity` | `CredentialChange` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `CredentialVerifier` | `@Slf4j` (rename `LOGGER` → `log`) | security-sensitive collaborator: isolate in its own batch |
| 6 | `identity` | `IdentityAdministration` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `IdentityAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 6 | `identity` | `IdentityAuthentication` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `IdentityErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 6 | `identity` | `IdentityRegistration` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `JdbcRecoveryRequestStore` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `JdbcRoleAssignmentStore` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `MfaChallenge` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `MfaEnrolmentService` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `RecoveryService` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `SessionIssue` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `SessionRevocation` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `SessionRotation` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 6 | `identity` | `TotpAlgorithm` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 6 | `identity` | `TotpVerifier` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch; identity/security bounded context: reviewed in its own module batch |
| 7 | `consent` | `ConsentAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 7 | `consent` | `ConsentErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 7 | `consent` | `ConsentGate` | `@RequiredArgsConstructor` + `@NonNull` fields | PII (consent) bounded context: reviewed in its own module batch |
| 7 | `kyc` | `CaseOpeningTrail` | `@RequiredArgsConstructor` + `@NonNull` fields | PII (KYC/KYB) bounded context: reviewed in its own module batch |
| 7 | `kyc` | `DocumentAccess` | `@RequiredArgsConstructor` + `@NonNull` fields | PII (KYC/KYB) bounded context: reviewed in its own module batch |
| 7 | `kyc` | `DocumentContentType` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 7 | `kyc` | `JdbcCheckStore` | `@RequiredArgsConstructor` + `@NonNull` fields | PII (KYC/KYB) bounded context: reviewed in its own module batch |
| 7 | `kyc` | `JdbcDocumentStore` | `@RequiredArgsConstructor` + `@NonNull` fields | PII (KYC/KYB) bounded context: reviewed in its own module batch |
| 7 | `kyc` | `KycAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 7 | `kyc` | `KycErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 7 | `party` | `OrganisationRegistration` | `@RequiredArgsConstructor` + `@NonNull` fields | PII (party) bounded context: reviewed in its own module batch |
| 7 | `party` | `PartyAuditAction` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 7 | `party` | `PartyErrorCode` | `@RequiredArgsConstructor` (enum) | a contract type (error codes, audit actions, purposes): constants, codes and ordinals must stay byte-identical; accessors stay fluent |
| 7 | `party` | `PartyRegistration` | `@RequiredArgsConstructor` + `@NonNull` fields | PII (party) bounded context: reviewed in its own module batch |
| 8 | `app` | `ApiVersionConfiguration` | `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` + `@NonNull` fields | package-private constructor: access = AccessLevel.PACKAGE; @Configuration class (CGLIB-proxied) |
| 8 | `app` | `AuthenticationController` | `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` + `@NonNull` fields | package-private class and constructor (access = AccessLevel.PACKAGE); authentication surface |
| 8 | `app` | `CheckoutPaymentParticipants` | `@RequiredArgsConstructor` + `@NonNull` fields | constructor documentation to move onto fields |
| 8 | `app` | `CheckoutSessionController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `CheckoutSessions` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `CheckoutTransactions` | `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` + `@NonNull` fields | package-private constructor: access = AccessLevel.PACKAGE |
| 8 | `app` | `CredentialController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `KycUnitOfWork` | `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` + `@NonNull` fields | package-private constructor: access = AccessLevel.PACKAGE |
| 8 | `app` | `MerchantApiKeyController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `MerchantApiKeyOperations` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `MerchantKeyAuthenticationInterceptor` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `MfaChallengeController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `MfaController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `PaymentWebhookController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `PaymentWebhookService` | `@Slf4j` | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `RecoveryController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `RegistrationController` | `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` + `@NonNull` fields | package-private constructor: access = AccessLevel.PACKAGE |
| 8 | `app` | `SessionAuthenticationInterceptor` | `@Slf4j` (rename `LOGGER` → `log`) + `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |
| 8 | `app` | `SessionController` | `@RequiredArgsConstructor` + `@NonNull` fields | security-sensitive collaborator: isolate in its own batch |

### C. DO NOT REFACTOR — 446 classes, by first reason and module

| Reason | `accounts` | `app` | `checkout` | `consent` | `identity` | `kyc` | `ledger` | `merchant` | `party` | `paymentmethods` | `payments` | `platform` | `sharedkernel` | `transfers` | Total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Constructor validates, derives, copies defensively, or there is no constructor boilerplate | 2 | 60 | 4 | 2 | 12 | 15 | 18 | 9 | 3 | 3 | 10 | 17 | 3 | 5 | **163** |
| Already a record |  | 38 |  | 1 | 10 | 1 | 9 | 3 | 1 | 3 | 8 | 13 | 3 | 3 | **93** |
| Exception type | 4 | 3 | 2 | 1 | 4 | 1 | 9 | 12 | 1 | 1 | 6 | 6 | 2 | 4 | **56** |
| Enum without an assign-only constructor | 2 |  | 1 | 2 | 10 | 11 | 8 | 3 | 2 | 1 | 5 | 3 |  | 4 | **52** |
| Fluent accessors only (`@Getter` would rename them `getX()`) | 1 | 1 | 1 | 2 | 3 | 3 | 3 | 1 | 1 | 1 | 7 | 3 | 4 | 3 | **34** |
| Private constructor behind an invariant-enforcing factory | 1 |  | 1 |  | 4 | 3 | 1 | 2 | 2 |  |  | 1 | 3 |  | **18** |
| Hand-written `equals`/`hashCode` |  |  | 2 |  | 1 | 3 | 1 | 2 |  |  |  |  | 1 |  | **10** |
| Constructor differs from what Lombok generates (renamed parameter, non-final field) |  | 11 |  |  |  |  |  |  |  |  |  |  |  |  | **11** |
| Hand-written `toString` (redaction or controlled output) |  |  |  |  | 3 | 2 |  | 1 |  |  | 2 |  |  |  | **8** |
| Money kernel (`RoundingPolicy`) |  |  |  |  |  |  |  |  |  |  |  |  | 1 |  | **1** |

**Named, because they are the classes this refactor most needs to leave alone** (hand-written equality or `toString`, or a private constructor behind a factory):

- `accounts`: `CustomerAccount`
- `checkout`: `CheckoutSession`, `CheckoutSessionToken`, `Order`
- `identity`: `Credential`, `Identity`, `MfaEnrolment`, `SecretCipher`, `Session`, `SessionToken`, `SingleUseToken`, `VerificationOutcome`
- `kyc`: `BeneficialOwner`, `CallbackSignature`, `DocumentBytes`, `DocumentCipher`, `KycCase`, `KycDocument`, `ReviewTask`, `VerificationCheck`
- `ledger`: `JournalEntry`, `LedgerAccount`
- `merchant`: `FeeRate`, `FeeSchedule`, `FeeScheduleVersion`, `MerchantApiKey`, `MerchantApiKeySecret`
- `party`: `Customer`, `Party`
- `payments`: `EvidenceCipher`, `WebhookSignature`
- `platform`: `RequestFingerprint`
- `sharedkernel`: `CausationId`, `CorrelationId`, `Money`, `Sensitive`
