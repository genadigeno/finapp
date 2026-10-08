package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.CLOCK;
import static com.finapp.app.credit.CreditWorld.IDS;
import static com.finapp.app.credit.CreditWorld.TRANSACTIONS;
import static com.finapp.app.credit.CreditWorld.count;
import static com.finapp.app.credit.CreditWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CanonicalSnapshot;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditBureau;
import com.finapp.credit.CreditDataCollection;
import com.finapp.credit.CreditDataRequestId;
import com.finapp.credit.CreditDataRequestStatus;
import com.finapp.credit.CreditDataSource;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditInvestigations;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditSourceKind;
import com.finapp.credit.DecisionProgress;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDecisions;
import com.finapp.credit.JdbcCreditReads;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.ScorecardModelVersionId;
import com.finapp.credit.SnapshotContent;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CountryCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Source selection between two bureaus over a real database (`P10-TSK-021`; ADR-0085 section 10, {@code INV-CRD-07},
 * {@code INV-CRD-03}, {@code INV-CRD-10}): the provider chosen at birth from configuration alone and named on the data
 * request, its audit, its record and its event; a disabled provider skipped at birth and never substituted after it;
 * every provider disabled or unavailable reaching the policy's fallback and never data; and the two bureaus' wires
 * normalising one person alike, so a request collected from {@code bureau-sim-b} decides, explains and re-derives exactly
 * as one collected from {@code bureau-sim-a}.
 *
 * <p>Every count is read from the bureaus' own engines or from the rows. Each collection built here stands for an
 * instance with its own configuration.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings policies into force and records
 * decisions that reserve exposure - the shared JVM's endpoint suites rely on the seeds still being proposals.
 */
@Tag("database")
@Tag("own-container")
@DisplayName("bureau source selection (P10-TSK-021)")
class BureauSelectionDatabaseTest {

    private static final byte[] KEY = "a-bureau-test-key-of-32-bytes-ok!".getBytes(StandardCharsets.UTF_8);
    private static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofMinutes(1), Duration.ofMinutes(30));

    /** Who each subject reference names - two parties may name one person, as two bureaus would see them. */
    private final Map<String, CreditDataSubject> people = new ConcurrentHashMap<>();

    private SimulatedBureauEngine engineA;
    private SimulatedBureauEngine engineB;
    private CreditBureau bureauA;
    private CreditBureau bureauB;

    @BeforeAll
    static void inForce() {
        CreditWorld.seedsInForce();
        // The seed's rules without the score rules: no outcome here is a REFER but the unavailable fallback's.
        CreditWorld.inForce(CreditProduct.PERSONAL_LOAN, 900, false);
    }

    @BeforeEach
    void start() throws Exception {
        engineA = SimulatedBureauEngine.start();
        engineB = SimulatedBureauEngine.startSecondBureau();
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        engineA.retrievedAt(recent);
        engineB.retrievedAt(recent);
        CreditDataSubjectResolver subjects = reference -> Optional.of(people.computeIfAbsent(reference,
                key -> new CreditDataSubject("Applicant " + key, LocalDate.of(1980, 1, 1), CountryCode.of("DE"))));
        bureauA = new SimulatedBureauAdapter(engineA.baseUrl(), Duration.ofMillis(800), KEY, subjects);
        bureauB = new SimulatedSecondBureauAdapter(engineB.baseUrl(), Duration.ofMillis(800), KEY, subjects);
    }

    @AfterEach
    void stop() {
        engineA.close();
        engineB.close();
    }

    // ------------------------------------------------------------------ selection at birth

    @Test
    @DisplayName("a disabled provider is skipped at birth: the next in order is named on the request, audited and asked")
    void aDisabledProviderIsSkippedAtBirth() {
        CreditDataCollection firstEnabled = collection(order(Set.of(), bureauA, bureauB));
        CreditDataRequestId toA = opened(firstEnabled.open(opening(UUID.randomUUID()), correlation()));
        assertThat(provider(toA)).as("nothing disabled: the first in order").isEqualTo(SimulatedBureauAdapter.CODE);

        CreditDataCollection aDisabled = collection(order(Set.of(SimulatedBureauAdapter.CODE), bureauA, bureauB));
        CreditDataRequestId toB = opened(aDisabled.open(opening(UUID.randomUUID()), correlation()));
        assertThat(provider(toB)).isEqualTo(SimulatedSecondBureauAdapter.CODE);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND operation ="
                + " 'credit.BureauDataRequested' AND change_summary LIKE '%provider=bureau-sim-b'", toB.value().toString()))
                .as("the access audited naming its provider").isEqualTo(1);
        assertThat(engineB.idempotencyKeys()).containsExactly("CDR-" + toB.value());
        assertThat(engineA.idempotencyKeys()).as("the disabled bureau was asked nothing for the second request")
                .containsExactly("CDR-" + toA.value());
        assertThat(status(toB)).isEqualTo(CreditDataRequestStatus.RECEIVED);
    }

    @Test
    @DisplayName("the provider is fixed at birth: a later disable never moves an open request, and a provider configured"
            + " out is unavailable - never a substitute under the same reference")
    void theProviderIsFixedAtBirthNeverSubstituted() {
        // Born on an instance that tries a first; the opener "crashes" before asking.
        CreditDataCollection birth = collection(order(Set.of(), bureauA, bureauB));
        CreditDataRequestId id = opened(TRANSACTIONS.inTransaction(
                uow -> birth.openWithin(uow, opening(UUID.randomUUID()), correlation())));
        assertThat(provider(id)).isEqualTo(SimulatedBureauAdapter.CODE);

        // An instance configured with a disabled asks it anyway: the request's provider, not this instance's choice.
        CreditDataCollection disabledSince = collection(order(Set.of(SimulatedBureauAdapter.CODE), bureauA, bureauB));
        CreditDataRequestId second = opened(TRANSACTIONS.inTransaction(
                uow -> birth.openWithin(uow, opening(UUID.randomUUID()), correlation())));
        assertThat(disabledSince.ask(id, correlation())).isEqualTo(CreditDataRequestStatus.RECEIVED);
        assertThat(engineA.idempotencyKeys()).containsExactly("CDR-" + id.value());
        assertThat(engineB.idempotencyKeys()).as("never a second provider under one reference").isEmpty();

        // An instance that no longer configures a at all: nothing asked of anyone, the request unavailable.
        CreditDataCollection withoutA = collection(order(Set.of(), bureauB));
        assertThat(withoutA.ask(second, correlation())).isEqualTo(CreditDataRequestStatus.UNAVAILABLE);
        assertThat(engineB.idempotencyKeys()).as("b is never asked under a's reference").isEmpty();
        assertThat(engineA.idempotencyKeys()).hasSize(1);
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ?", second.value()))
                .isZero();
        assertThat(provider(second)).as("the request still names the provider it was born naming")
                .isEqualTo(SimulatedBureauAdapter.CODE);
    }

    // ------------------------------------------------------------------ provenance

    @Test
    @DisplayName("the record names its provider - on the request, the record, the event, and every stored attribute")
    void theRecordNamesItsProvider() {
        UUID party = UUID.randomUUID();
        CreditDataCollection collection = collection(order(Set.of(SimulatedBureauAdapter.CODE), bureauA, bureauB));
        CreditDataRequestId id = opened(collection.open(opening(party), correlation()));
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.RECEIVED);
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ? AND provider_code ="
                + " 'bureau-sim-b' AND normaliser_version = 1 AND source_kind = 'BUREAU'", id.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_record_attribute a JOIN credit.credit_record r"
                + " ON a.record_id = r.id WHERE r.data_request_id = ?", id.value())).isEqualTo(CreditBureau.ATTRIBUTES.size());
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = '"
                + CreditDataCollection.COLLECTED_EVENT + "' AND convert_from(payload, 'UTF8') LIKE"
                + " '%\"provider\":\"bureau-sim-b\"%'", id.value())).as("the event names its provider").isEqualTo(1);

        // Read back as the snapshot freezer reads it - the provenance every frozen attribute will carry names b
        // (and the explanation shows it: #aRequestCollectedFromBDecidesExplainsAndReplaysAsOneFromA).
        com.finapp.credit.DecisionSnapshotStore.StoredRecord stored = TRANSACTIONS.inTransaction(
                uow -> new JdbcDecisionSnapshotStore().recordOf(uow, id, Duration.ofDays(30)).orElseThrow());
        assertThat(stored.providerCode()).isEqualTo(SimulatedSecondBureauAdapter.CODE);
        assertThat(stored.kind()).isEqualTo(CreditSourceKind.BUREAU);
        assertThat(stored.normaliserVersion()).isEqualTo(SimulatedSecondBureauAdapter.NORMALISER_VERSION);
        assertThat(stored.attributes()).hasSize(CreditBureau.ATTRIBUTES.size());
    }

    @Test
    @DisplayName("an answer naming another provider than the request's is never recorded - nothing born, still REQUESTED")
    void anAnswerNamingAnotherProviderIsNeverRecorded() {
        // Registered as b, answering as a: a misrouted or mislabelled adapter - the record must not name a provider the
        // request was not born naming (INV-CRD-07).
        CreditBureau mislabelled = new CreditBureau() {
            @Override
            public String code() {
                return SimulatedSecondBureauAdapter.CODE;
            }

            @Override
            public com.finapp.credit.CreditDataAnswer pull(com.finapp.credit.CreditDataPull request) {
                return bureauA.pull(request);
            }
        };
        CreditDataCollection collection = collection(order(Set.of(), mislabelled));
        CreditDataRequestId id = opened(TRANSACTIONS.inTransaction(
                uow -> collection.openWithin(uow, opening(UUID.randomUUID()), correlation())));
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> collection.ask(id, correlation()))
                .withMessageContaining("bureau-sim-a").withMessageContaining("bureau-sim-b");
        assertThat(status(id)).isEqualTo(CreditDataRequestStatus.REQUESTED);
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id = ?", id.value())).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?", id.value())).isZero();
    }

    // ------------------------------------------------------------------ the fail-safe

    @Test
    @DisplayName("both providers disabled or unavailable: UNAVAILABLE to the deadline and the policy's fallback - never data")
    void bothUnavailableFallsBackNeverData() throws Exception {
        CreditDataCollection.Timing brief = new CreditDataCollection.Timing(Duration.ofMillis(300), Duration.ofSeconds(2));

        // Both disabled: the birth names the fail-safe, whose every pull is unavailable; neither bureau is asked.
        CreditDataCollection bothDisabled = collection(new CreditDataCollection.Configured(List.of(bureauA, bureauB),
                Set.of(SimulatedBureauAdapter.CODE, SimulatedSecondBureauAdapter.CODE), Optional.of(new UnconfiguredBureau()),
                brief));
        CreditDataRequestId none = opened(bothDisabled.open(opening(UUID.randomUUID()), correlation()));
        assertThat(provider(none)).isEqualTo(UnconfiguredBureau.CODE);
        assertThat(status(none)).isEqualTo(CreditDataRequestStatus.UNAVAILABLE);

        // a disabled, b down: b's outage is the request's - there is no failover to a, under any reference.
        engineB.arm(SimulatedBureauEngine.Fault.UNAVAILABLE);
        CreditDataCollection bDown = collection(new CreditDataCollection.Configured(List.of(bureauA, bureauB),
                Set.of(SimulatedBureauAdapter.CODE), Optional.of(new UnconfiguredBureau()), brief));
        CreditDataRequestId down = opened(bDown.open(opening(UUID.randomUUID()), correlation()));
        assertThat(provider(down)).isEqualTo(SimulatedSecondBureauAdapter.CODE);
        assertThat(status(down)).isEqualTo(CreditDataRequestStatus.UNAVAILABLE);

        CreditWorld.awaitDatabase("SELECT bool_and(deadline_at <= statement_timestamp()) FROM credit.data_request"
                + " WHERE id IN (?, ?)", none.value(), down.value());
        for (int i = 0; i < 20 && count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id IN (?, ?) AND"
                + " event_type = '" + CreditDataCollection.UNAVAILABLE_EVENT + "'", none.value(), down.value()) < 2; i++) {
            bDown.reportOverdue(50, correlation());
        }
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = '"
                + CreditDataCollection.UNAVAILABLE_EVENT + "' AND convert_from(payload, 'UTF8') LIKE"
                + " '%\"provider\":\"bureau-sim-b\"%'", down.value())).as("the outage names its provider").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_record WHERE data_request_id IN (?, ?)", none.value(),
                down.value())).as("never data").isZero();
        assertThat(engineA.idempotencyKeys()).as("the disabled bureau is never a failover").isEmpty();

        // Through a decision: the policy's declared fallback refers it, and nothing approves.
        engineB.arm(SimulatedBureauEngine.Fault.UNAVAILABLE);
        UUID party = UUID.randomUUID();
        UUID request = CreditWorld.request(party, CreditProduct.PERSONAL_LOAN, CreditWorld.eur(500_000), Duration.ofDays(7));
        CreditDataCollection world = CreditWorld.collection(CreditDataCollection.Sources.of(
                new CreditDataCollection.Configured(List.of(bureauA, bureauB), Set.of(SimulatedBureauAdapter.CODE),
                        Optional.of(new UnconfiguredBureau()), brief),
                new CreditDataCollection.Configured(CreditWorld.FINDATA, TIMING)));
        DecisionProgress progress = CreditWorld.progress(CLOCK, CreditWorld.deciding(CLOCK, world), world);
        driveUntilEvaluated(progress, request);
        assertThat(scalar("SELECT outcome FROM credit.policy_evaluation WHERE decision_request_id = ?", request))
                .isEqualTo("REFER");
        // The policy's declared fallback for an unavailable source is its SOURCE_UNAVAILABLE rule's REFER (ADR-0085 point 7).
        assertThat(scalar("SELECT array_to_string(reason_codes, ',') FROM credit.policy_evaluation"
                + " WHERE decision_request_id = ?", request)).contains("CRD-SOURCE-UNAVAILABLE");
        assertThat(count("SELECT count(*) FROM credit.credit_decision WHERE decision_request_id = ? AND outcome LIKE"
                + " 'APPROVE%'", request)).as("never an approval").isZero();
        assertThat(count("SELECT count(*) FROM credit.data_request WHERE decision_request_id = ? AND source_kind ="
                + " 'BUREAU' AND provider_code = 'bureau-sim-b' AND status = 'UNAVAILABLE'", request)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ provider neutrality

    @Test
    @DisplayName("both providers normalise one subject alike - every stored attribute, code for code and value for value")
    void bothProvidersNormaliseOneSubjectAlike() {
        CreditDataCollection fromA = collection(order(Set.of(), bureauA));
        CreditDataCollection fromB = collection(order(Set.of(), bureauB));
        boolean delinquencyDistinguishable = false;
        for (int person = 0; person < 6; person++) {
            CreditDataSubject subject = new CreditDataSubject("Neutral Person " + person,
                    LocalDate.of(1970 + person, 1 + person, 1 + person), CountryCode.of(person % 2 == 0 ? "DE" : "FR"));
            UUID partyA = UUID.randomUUID();
            UUID partyB = UUID.randomUUID();
            people.put(partyA.toString(), subject);
            people.put(partyB.toString(), subject);
            CreditDataRequestId a = opened(fromA.open(opening(partyA), correlation()));
            CreditDataRequestId b = opened(fromB.open(opening(partyB), correlation()));
            Map<String, String> fromBureauA = storedAttributes(a);
            Map<String, String> fromBureauB = storedAttributes(b);
            assertThat(fromBureauA).as("person %d", person).hasSize(CreditBureau.ATTRIBUTES.size());
            assertThat(fromBureauB).as("person %d, bureau-sim-b against bureau-sim-a", person).isEqualTo(fromBureauA);
            delinquencyDistinguishable |= !fromBureauA.get("BUREAU_DELINQUENCIES_24M")
                    .equals(fromBureauA.get("BUREAU_DEFAULTS_72M"));
        }
        assertThat(delinquencyDistinguishable)
                .as("not vacuous: some person's delinquencies and defaults differ, so a swapped mapping shows")
                .isTrue();
    }

    @Test
    @DisplayName("a request collected from bureau-sim-b decides, explains and re-derives exactly as one from bureau-sim-a")
    void aRequestCollectedFromBDecidesExplainsAndReplaysAsOneFromA() {
        CreditDataSubject subject = new CreditDataSubject("Same Person", LocalDate.of(1984, 6, 30), CountryCode.of("DE"));
        UUID partyA = UUID.randomUUID();
        UUID partyB = UUID.randomUUID();
        people.put(partyA.toString(), subject);
        people.put(partyB.toString(), subject);
        CreditInvestigations.Explanation viaA = decideAndExplain(partyA, bureauA);
        CreditInvestigations.Explanation viaB = decideAndExplain(partyB, bureauB);

        assertThat(viaB.decision().outcome()).isEqualTo(viaA.decision().outcome());
        assertThat(viaB.decision().approved()).isEqualTo(viaA.decision().approved());
        assertThat(viaB.decision().reasons()).isEqualTo(viaA.decision().reasons());
        assertThat(viaB.evaluation()).as("the policy's evaluation, rule for rule").isEqualTo(viaA.evaluation());
        assertThat(viaB.score()).isEqualTo(viaA.score());
        assertThat(viaB.rules()).isEqualTo(viaA.rules());
        assertThat(values(viaB.attributes())).as("the explained attributes").isEqualTo(values(viaA.attributes()));
        assertThat(bureauProviders(viaA)).containsOnly(SimulatedBureauAdapter.CODE);
        assertThat(bureauProviders(viaB)).as("only the provenance differs").containsOnly(SimulatedSecondBureauAdapter.CODE);

        // Re-derived from the stored rows alone: the canonical snapshot's hash re-verified and its score re-computed under
        // the pinned model - for both, equal (P10-TSK-019's replayer will re-run the whole engine over these rows).
        assertThat(rederivedScore(viaB)).isEqualTo(viaB.score()).isEqualTo(rederivedScore(viaA));
    }

    // ------------------------------------------------------------------ harness

    private CreditInvestigations.Explanation decideAndExplain(UUID party, CreditBureau bureau) {
        CreditDataCollection world = CreditWorld.collection(CreditDataCollection.Sources.of(
                new CreditDataCollection.Configured(bureau, TIMING),
                new CreditDataCollection.Configured(CreditWorld.FINDATA, TIMING)));
        UUID request = CreditWorld.request(party, CreditProduct.PERSONAL_LOAN, CreditWorld.eur(500_000), Duration.ofDays(7));
        DecisionProgress progress = CreditWorld.progress(CLOCK, CreditWorld.deciding(CLOCK, world), world);
        for (int i = 0; i < 10 && !"DECIDED".equals(statusOf(request)); i++) {
            CreditWorld.step(progress, request);
        }
        assertThat(statusOf(request)).as("decided, from " + bureau.code()).isEqualTo("DECIDED");
        UUID decision = UUID.fromString(scalar("SELECT id FROM credit.credit_decision WHERE decision_request_id = ?",
                request));
        CreditInvestigations investigations = new CreditInvestigations(new JdbcCreditDecisions(),
                new JdbcDecisionSnapshotStore(), new JdbcCreditAssessmentStore(), new JdbcPolicyEvaluationStore(),
                CreditWorld.POLICIES, CreditWorld.SCORECARDS, new JdbcCreditReads(), CreditWorld.cipher,
                new JdbcAuditWriter(), IDS, CLOCK);
        Actor officer = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        return TRANSACTIONS.inTransaction(uow -> investigations.explain(uow, CreditDecisionId.of(decision), officer,
                correlation())).orElseThrow();
    }

    private static int rederivedScore(CreditInvestigations.Explanation explanation) {
        UUID snapshot = explanation.decision().snapshot().value();
        String canonical = scalar("SELECT canonical FROM credit.decision_snapshot WHERE id = ?", snapshot);
        assertThat(scalar("SELECT encode(content_sha256, 'hex') FROM credit.decision_snapshot WHERE id = ?", snapshot))
                .isEqualTo(java.util.HexFormat.of().formatHex(CanonicalSnapshot.sha256(canonical)));
        SnapshotContent content = CanonicalSnapshot.parse(canonical);
        return TRANSACTIONS.inTransaction(uow -> CreditWorld.SCORECARDS.model(uow,
                ScorecardModelVersionId.of(content.versions().modelVersion())).orElseThrow().scorecard().score(content));
    }

    private static Map<CreditAttributeCode, AttributeValue> values(List<CreditInvestigations.ExplainedAttribute> attributes) {
        Map<CreditAttributeCode, AttributeValue> values = new TreeMap<>();
        attributes.forEach(attribute -> values.put(attribute.code(), attribute.value()));
        return values;
    }

    private static Set<String> bureauProviders(CreditInvestigations.Explanation explanation) {
        Set<String> providers = new java.util.HashSet<>();
        for (CreditInvestigations.ExplainedAttribute attribute : explanation.attributes()) {
            if (attribute.provenance() instanceof AttributeProvenance.Record record
                    && record.kind() == CreditSourceKind.BUREAU) {
                providers.add(record.providerCode());
            }
        }
        assertThat(providers).as("the bureau's attributes are in the explanation").isNotEmpty();
        return providers;
    }

    private static void driveUntilEvaluated(DecisionProgress progress, UUID request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (count("SELECT count(*) FROM credit.policy_evaluation WHERE decision_request_id = ?", request) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never evaluated; the request is " + statusOf(request));
            }
            CreditWorld.step(progress, request);
            Thread.sleep(200);
        }
    }

    /** A bureau order: {@code providers} in order, {@code disabled} skipped, the fail-safe last. */
    private static CreditDataCollection.Configured order(Set<String> disabled, CreditDataSource... providers) {
        return new CreditDataCollection.Configured(List.of(providers), disabled, Optional.of(new UnconfiguredBureau()),
                TIMING);
    }

    private static CreditDataCollection collection(CreditDataCollection.Configured bureaus) {
        return CreditWorld.collection(CreditDataCollection.Sources.of(bureaus,
                new CreditDataCollection.Configured(new UnconfiguredFinancialData(), TIMING)));
    }

    private static CreditDataCollection.Opening opening(UUID party) {
        UUID decision = TRANSACTIONS.inTransaction(
                uow -> DecisionRequestRows.submitted(uow, party, CreditProduct.PERSONAL_LOAN));
        return new CreditDataCollection.Opening(decision, party, CreditProduct.PERSONAL_LOAN, CreditSourceKind.BUREAU);
    }

    private static CreditDataRequestId opened(CreditDataCollection.Opened opened) {
        assertThat(opened).isInstanceOf(CreditDataCollection.Opened.Requested.class);
        return ((CreditDataCollection.Opened.Requested) opened).id();
    }

    /** The record's stored attributes, code to a typed rendering - read from the rows, never the collection's say-so. */
    private static Map<String, String> storedAttributes(CreditDataRequestId id) {
        Map<String, String> attributes = new TreeMap<>();
        List<String> codes = new ArrayList<>();
        String joined = scalar("SELECT string_agg(a.code || '=' || a.value_type || ':' || coalesce(a.integer_value::text,"
                + " a.money_minor::text || ' ' || a.money_currency || '/' || a.money_scale, a.boolean_value::text,"
                + " a.code_value, 'ABSENT'), ';' ORDER BY a.code) FROM credit.credit_record_attribute a"
                + " JOIN credit.credit_record r ON a.record_id = r.id WHERE r.data_request_id = ?", id.value());
        assertThat(joined).as("a record was stored").isNotNull();
        for (String pair : joined.split(";")) {
            String[] parts = pair.split("=", 2);
            codes.add(parts[0]);
            attributes.put(parts[0], parts[1]);
        }
        assertThat(codes).doesNotHaveDuplicates();
        return attributes;
    }

    private static String provider(CreditDataRequestId id) {
        return scalar("SELECT provider_code FROM credit.data_request WHERE id = ?", id.value());
    }

    private static CreditDataRequestStatus status(CreditDataRequestId id) {
        return CreditDataRequestStatus.valueOf(scalar("SELECT status FROM credit.data_request WHERE id = ?", id.value()));
    }

    private static String statusOf(UUID request) {
        return scalar("SELECT status FROM credit.decision_request WHERE id = ?", request);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
