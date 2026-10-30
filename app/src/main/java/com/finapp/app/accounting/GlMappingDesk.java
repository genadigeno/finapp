package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlMappings;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.StatementSection;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the GL mapping doors (`P14-TSK-003`; risk's {@code RiskPolicyDesk}): parse the proposal exactly
 * into the domain's words - an unparseable month, an unknown classification or section - each
 * {@code accounting.MappingInvalid} before anything is keyed; key every act per principal
 * ({@code accounting.mapping:<type>:<id>}), run each in one transaction under its span, and translate the domain's
 * refusals onto the error contract; a refused self-approval is recorded {@code FAILED} in a transaction of its own.
 * The four-eyes rule, the machine, the frozen content and the judgement against the chart are the domain's and
 * {@code accounting V002}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class GlMappingDesk {

    static final String SCOPE = "accounting.mapping:";

    static final String PROPOSE_SPAN = "accounting.mapping.propose";
    static final String ACTIVATE_SPAN = "accounting.mapping.activate";
    static final String REJECT_SPAN = "accounting.mapping.reject";
    static final String READ_SPAN = "accounting.mapping.read";

    @NonNull private final GlMappingAdministration administration;
    @NonNull private final GlMappings mappings;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    /** A version's receipt: its id, number, effective month and status, and the predecessor an activation retired. */
    public record GlMappingReceipt(String id, int version, String effectiveFromPeriod, String status, Integer retiredVersion) {}

    /** A GL account as the operator reads it. */
    public record GlMappingAccountView(String code, String name, String classification, String section, String normalBalance) {

        static GlMappingAccountView of(GlAccount account) {
            return new GlMappingAccountView(account.code(), account.name(), account.classification().name(),
                    account.section().name(), account.normalBalance());
        }
    }

    /** A rule as the operator reads it. */
    public record GlMappingRuleView(String purpose, String ownerKind, String glAccountCode) {

        static GlMappingRuleView of(MappingRule rule) {
            return new GlMappingRuleView(rule.purpose(), rule.ownerKind(), rule.glAccountCode());
        }
    }

    /** A version: its machine row and its whole content. No person, no reason. */
    public record GlMappingVersionView(
            String id,
            int version,
            String status,
            String effectiveFromPeriod,
            String effectiveToPeriod,
            String proposedAt,
            String decidedAt,
            String retiredAt,
            List<GlMappingAccountView> accounts,
            List<GlMappingRuleView> rules) {}

    /** A GL account changed between the base and the version. */
    public record GlMappingAccountChangeView(String code, GlMappingAccountView before, GlMappingAccountView after) {}

    /** A rule whose GL account changed. */
    public record GlMappingRuleChangeView(String purposeAndOwnerKind, String before, String after) {}

    /** A counterparty - a dimension under its purpose's GL account. */
    public record GlMappingCounterpartyView(String code, String kind) {}

    /**
     * A version against its base - the version in force, or the one named - and its judgement against the ledger's chart
     * now: {@code activatable} is false while a purpose is unmapped, a rule names no purpose, or a classification is not
     * its purpose's type.
     */
    public record GlMappingDiffView(
            String id,
            int version,
            String status,
            String effectiveFromPeriod,
            String againstId,
            Integer againstVersion,
            List<GlMappingAccountView> accountsAdded,
            List<GlMappingAccountView> accountsRemoved,
            List<GlMappingAccountChangeView> accountsChanged,
            List<GlMappingRuleView> rulesAdded,
            List<GlMappingRuleView> rulesRemoved,
            List<GlMappingRuleChangeView> rulesChanged,
            List<String> unmapped,
            List<String> unknown,
            List<String> incompatible,
            boolean activatable,
            List<GlMappingCounterpartyView> counterparties) {}

    public GlMappingReceipt propose(String idempotencyKey, GlMappingController.GlMappingProposalRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        GlMappingVersion content = guarded(() -> content(request));
        return flow.door(PROPOSE_SPAN, () -> keyed(actor, idempotencyKey, "propose|" + request, uow -> {
            GlMappingAdministration.Proposed proposed = administration.propose(uow, content, request.reason(), actor,
                    correlation);
            return new GlMappingReceipt(proposed.id().value().toString(), proposed.version(),
                    proposed.effectiveFrom().toString(), "PROPOSED", null);
        }));
    }

    public GlMappingReceipt activate(String idempotencyKey, String rawId, String reason) {
        GlMappingVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(ACTIVATE_SPAN, () -> keyed(actor, idempotencyKey, "activate|" + id.value() + "|" + reason,
                uow -> receipt(administration.activate(uow, id, actor, reason, correlation))));
    }

    public GlMappingReceipt reject(String idempotencyKey, String rawId, String reason) {
        GlMappingVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REJECT_SPAN, () -> keyed(actor, idempotencyKey, "reject|" + id.value() + "|" + reason,
                uow -> receipt(administration.reject(uow, id, actor, reason, correlation))));
    }

    /** One version by id - a read, unkeyed, in one transaction. */
    public GlMappingVersionView version(String rawId) {
        GlMappingVersionId id = versionId(rawId);
        return guarded(() -> flow.door(READ_SPAN,
                () -> transactions.inTransaction(uow -> view(mappings.version(uow, id)))));
    }

    /** The version against its base, judged against the chart now - a read, unkeyed, in one transaction. */
    public GlMappingDiffView diff(String rawId, String rawAgainst) {
        GlMappingVersionId id = versionId(rawId);
        Optional<GlMappingVersionId> against = rawAgainst == null ? Optional.empty() : Optional.of(versionId(rawAgainst));
        return guarded(() -> flow.door(READ_SPAN,
                () -> transactions.inTransaction(uow -> diffView(mappings.diff(uow, id, against)))));
    }

    // ------------------------------------------------------------------ the version in the domain's words

    static GlMappingVersion content(GlMappingController.GlMappingProposalRequest request) {
        YearMonth effectiveFrom;
        try {
            effectiveFrom = YearMonth.parse(request.effectiveFromPeriod());
        } catch (DateTimeParseException | NullPointerException malformed) {
            throw new GlMappingVersion.MappingInvalid("the effective period is a calendar month, YYYY-MM");
        }
        List<GlAccount> accounts = new ArrayList<>();
        for (GlMappingController.GlMappingAccountRequest account : request.accounts()) {
            accounts.add(new GlAccount(account.code(), account.name(),
                    named(GlClassification.class, account.classification(), "a GL classification"),
                    named(StatementSection.class, account.section(), "a statement section")));
        }
        List<MappingRule> rules = new ArrayList<>();
        for (GlMappingController.GlMappingRuleRequest rule : request.rules()) {
            rules.add(new MappingRule(rule.purpose(), rule.ownerKind(), rule.glAccountCode()));
        }
        return new GlMappingVersion(effectiveFrom, accounts, rules);
    }

    private static <E extends Enum<E>> E named(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new GlMappingVersion.MappingInvalid("the version names something that is not " + what);
        }
    }

    // ------------------------------------------------------------------ the views

    static GlMappingVersionView view(GlMappings.Version version) {
        GlMappingStore.VersionRow row = version.row();
        return new GlMappingVersionView(
                row.id().value().toString(),
                row.version(),
                row.status().name(),
                row.effectiveFrom().toString(),
                row.effectiveTo().map(YearMonth::toString).orElse(null),
                row.proposedAt().toString(),
                row.decidedAt().map(Instant::toString).orElse(null),
                row.retiredAt().map(Instant::toString).orElse(null),
                version.content().accounts().stream().map(GlMappingAccountView::of).toList(),
                version.content().rules().stream().map(GlMappingRuleView::of).toList());
    }

    static GlMappingDiffView diffView(GlMappings.Diff diff) {
        GlMappingStore.VersionRow row = diff.version().row();
        return new GlMappingDiffView(
                row.id().value().toString(),
                row.version(),
                row.status().name(),
                row.effectiveFrom().toString(),
                diff.base().map(base -> base.row().id().value().toString()).orElse(null),
                diff.base().map(base -> base.row().version()).orElse(null),
                diff.accountsAdded().stream().map(GlMappingAccountView::of).toList(),
                diff.accountsRemoved().stream().map(GlMappingAccountView::of).toList(),
                diff.accountsChanged().stream().map(change -> new GlMappingAccountChangeView(change.code(),
                        GlMappingAccountView.of(change.before()), GlMappingAccountView.of(change.after()))).toList(),
                diff.rulesAdded().stream().map(GlMappingRuleView::of).toList(),
                diff.rulesRemoved().stream().map(GlMappingRuleView::of).toList(),
                diff.rulesChanged().stream().map(change -> new GlMappingRuleChangeView(change.key(), change.before(),
                        change.after())).toList(),
                diff.judgement().unmapped(),
                diff.judgement().unknown(),
                diff.judgement().incompatible(),
                diff.judgement().total() && diff.judgement().compatible(),
                diff.counterparties().stream().map(c -> new GlMappingCounterpartyView(c.code(), c.kind())).toList());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private GlMappingReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, GlMappingReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    GlMappingReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.version() + "|" + receipt.effectiveFromPeriod() + "|"
                            + receipt.status() + "|"
                            + (receipt.retiredVersion() == null ? "" : receipt.retiredVersion());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a mapping act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new GlMappingReceipt(stored[0], Integer.parseInt(stored[1]), stored[2], stored[3],
                stored[4].isEmpty() ? null : Integer.valueOf(stored[4]));
    }

    private static GlMappingReceipt receipt(GlMappingAdministration.Decided decided) {
        return new GlMappingReceipt(decided.id().value().toString(), decided.version(),
                decided.effectiveFrom().toString(), decided.status().name(), decided.retired().orElse(null));
    }

    /**
     * {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own after the act rolled back,
     * then refused as before.
     */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (GlMappingAdministration.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                administration.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. */
    static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (GlMappingAdministration.MappingNotFound unknown) {
            throw notFound();
        } catch (GlMappingAdministration.SelfApprovalRefused self) {
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (GlMappingAdministration.MappingStale stale) {
            throw refused(AccountingErrorCode.MAPPING_STALE, stale);
        } catch (GlMappingAdministration.MappingRetroactive retroactive) {
            throw refused(AccountingErrorCode.MAPPING_RETROACTIVE, retroactive);
        } catch (GlMappingAdministration.MappingNotTotal gap) {
            throw refused(AccountingErrorCode.MAPPING_NOT_TOTAL, gap);
        } catch (GlMappingAdministration.MappingIncompatible incompatible) {
            throw refused(AccountingErrorCode.MAPPING_INCOMPATIBLE, incompatible);
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        } catch (GlMappingVersion.MappingInvalid invalid) {
            throw refused(AccountingErrorCode.MAPPING_INVALID, invalid);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A GL mapping command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No GL mapping version answers the request",
                "no such record.");
    }

    private static GlMappingVersionId versionId(String raw) {
        try {
            return GlMappingVersionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a GL mapping command runs inside a correlation scope"))
                .correlationId();
    }
}
