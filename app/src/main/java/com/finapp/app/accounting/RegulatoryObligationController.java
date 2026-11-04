package com.finapp.app.accounting;

import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.RequiresIdempotencyKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The regulatory obligation's doors (`P14-TSK-018`; ADR-0118 decision 1; {@code INV-AUD-04}): configuration as data under four eyes, a row's only way in - nothing is seeded, and every row is {@code REFERENCE}. A {@code REGULATORY_REPORTER} proposes and reads under {@code REGULATORY_REPORT_PREPARE}; a
 * {@code FINANCIAL_CONTROLLER} reads what they decide and what waits on them, activates from a {@code MULTI_FACTOR} session naming the configuration's hash, and rejects, under {@code REGULATORY_REPORT_APPROVE}. Never SQL or script from a body: a rule is a type and line codes, a figure a path in a closed grammar.
 */
@RestController
@RequestMapping(path = "/operator/regulatory/obligations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class RegulatoryObligationController {

    @NonNull private final RegulatoryObligationDesk desk;

    /** Proposes an obligation version - nothing can be drafted from it until a second person activates it. A reason is required. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    @RequiresIdempotencyKey
    @ResponseStatus(HttpStatus.CREATED)
    public RegulatoryObligationDesk.RegulatoryObligationReceipt proposeRegulatoryObligation(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @Valid @RequestBody RegulatoryObligationRequest body) {
        return desk.propose(idempotencyKey, new RegulatoryObligationDesk.Stated(body.jurisdictionCode(), body.formCode(), body.formVersion(), body.adapterName(), body.sources() == null ? null : body.sources().stream()
                .map(source -> new RegulatoryObligationDesk.RegulatoryObligationSourceItem(source.definition(), source.version())).toList(), body.lines() == null ? null : body.lines().stream()
                .map(line -> new RegulatoryObligationDesk.RegulatoryObligationLineItem(line.code(), line.label(), line.source(), line.figure())).toList(), body.rules() == null ? null : body.rules().stream()
                .map(rule -> new RegulatoryObligationDesk.RegulatoryObligationRuleItem(rule.code(), rule.type(), rule.left(), rule.right())).toList(), body.reason()));
    }

    /** The latest versions, newest first. Audited. */
    @GetMapping
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    public List<RegulatoryObligationDesk.RegulatoryObligationSummaryView> listRegulatoryObligations() {
        return desk.recent();
    }

    /** What this controller can decide: those proposed, never their own. Audited. */
    @GetMapping("/awaiting-activation")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    public List<RegulatoryObligationDesk.RegulatoryObligationSummaryView> listRegulatoryObligationsAwaitingActivation() {
        return desk.awaitingActivation();
    }

    /** One version as a preparer reads it. Audited. */
    @GetMapping("/{obligationId}")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_PREPARE)
    public RegulatoryObligationDesk.RegulatoryObligationView readRegulatoryObligation(@PathVariable("obligationId") String obligationId) {
        return desk.read(obligationId);
    }

    /** What a controller decides, with the proposer's reason, under their own permission. Audited. */
    @GetMapping("/{obligationId}/attestation")
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    public RegulatoryObligationDesk.RegulatoryObligationView readRegulatoryObligationAttestation(@PathVariable("obligationId") String obligationId) {
        return desk.attestation(obligationId);
    }

    /** Activates it - a second person, from a MULTI_FACTOR session, naming the configuration's hash; the version in force retires. A reason is required. */
    @PostMapping(path = "/{obligationId}/activation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    @RequiresIdempotencyKey
    public RegulatoryObligationDesk.RegulatoryObligationReceipt activateRegulatoryObligation(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("obligationId") String obligationId,
            @Valid @RequestBody RegulatoryObligationActivationRequest body) {
        return desk.activate(idempotencyKey, obligationId, body.configurationSha256(), body.reason());
    }

    /** Rejects it - final; may be the proposer's. A reason is required. */
    @PostMapping(path = "/{obligationId}/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.REGULATORY_REPORT_APPROVE)
    @RequiresIdempotencyKey
    public RegulatoryObligationDesk.RegulatoryObligationReceipt rejectRegulatoryObligation(
            @RequestHeader(IdempotencyKeyHeader.NAME) String idempotencyKey,
            @PathVariable("obligationId") String obligationId,
            @Valid @RequestBody RegulatoryObligationRejectionRequest body) {
        return desk.reject(idempotencyKey, obligationId, body.reason());
    }

    // -----------------------------------------------------------------

    /** The proposal: the form, the adapter, the sources, the line mapping, the rules, and why. */
    @ClosedBody
    public record RegulatoryObligationRequest(
            @Size(max = 25) String jurisdictionCode,
            @Size(max = 64) String formCode,
            @Max(9999) int formVersion,
            @Size(max = 64) String adapterName,
            @Size(max = 8) List<@Valid @NotNull RegulatoryObligationSourceRequest> sources,
            @Size(max = 500) List<@Valid @NotNull RegulatoryObligationLineRequest> lines,
            @Size(max = 200) List<@Valid @NotNull RegulatoryObligationRuleRequest> rules,
            @Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryObligationRequest[" + formCode + "/" + formVersion + "]";
        }
    }

    /** A report definition the form draws from. */
    @ClosedBody
    public record RegulatoryObligationSourceRequest(@Size(max = 64) String definition, @Max(9999) int version) {}

    /** One form line. */
    @ClosedBody
    public record RegulatoryObligationLineRequest(@Size(max = 32) String code, @Size(max = 200) String label, @Size(max = 64) String source, @Size(max = 256) String figure) {}

    /** One rule: a type of the closed vocabulary over line codes. */
    @ClosedBody
    public record RegulatoryObligationRuleRequest(@Size(max = 32) String code, @Size(max = 32) String type, @Size(max = 100) List<@NotNull @Size(max = 32) String> left, @Size(max = 100) List<@NotNull @Size(max = 32) String> right) {}

    /** The activation: what the controller read, and why. */
    @ClosedBody
    public record RegulatoryObligationActivationRequest(@Size(max = 64) String configurationSha256, @Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryObligationActivationRequest[" + configurationSha256 + "]";
        }
    }

    /** The rejection's reason. */
    @ClosedBody
    public record RegulatoryObligationRejectionRequest(@Size(max = 1000) String reason) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryObligationRejectionRequest[]";
        }
    }
}
