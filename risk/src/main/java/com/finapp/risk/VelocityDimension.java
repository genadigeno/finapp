package com.finapp.risk;

import java.util.regex.Pattern;

/**
 * What a velocity window counts movements <em>per</em> (`P13-TSK-006`; {@code PHASE_13_PLAN.md} section 12.4;
 * {@code INV-RSK-03}) - the velocity dimension a {@link RuleKind#VELOCITY_COUNT} or {@link RuleKind#VELOCITY_SUM} rule
 * names ({@link RuleKind.ParameterType#VELOCITY_DIMENSION}) and the first column of a {@code velocity_window} row's key.
 *
 * <p><strong>Not a {@link SubjectKind}</strong> (the coordinator's decision at wave C, from `P13-TSK-003`'s finding):
 * a subject kind says what a thing <em>is</em>, a dimension says which <em>role</em> it plays in a movement. The source
 * and the destination of one transfer are both accounts, and "ten per day per source account" must not count the
 * transfers an account received - so the two account roles are two dimensions. {@link #DESTINATION} is an account or a
 * cross-border beneficiary: where the money goes, whichever kind of thing receives it.
 *
 * <p>Declared in the order the windows are locked and upserted (R2a, R2): a reordering is a reviewed change to the
 * lock order, the canonical evaluation text's velocity order and {@code RiskVocabulariesTest}. {@link #SESSION} was
 * appended by `P13-TSK-014` (last, so no existing order moved): {@link #DEVICE} could not count "a session confirming
 * many checkouts" - no seam carries a device, identity holding no per-device identifier (`-008`, `-010`).
 */
public enum VelocityDimension {

    /** The party behind the movement - the customer, whichever account or instrument it used. */
    PARTY,

    /** The account the money leaves. */
    SOURCE_ACCOUNT,

    /** Where the money goes - an account, or a cross-border beneficiary. */
    DESTINATION,

    /** The device the movement was made from, by identity's hash only (OQ-4; {@link DeviceHash}). */
    DEVICE,

    /** The merchant paid. */
    MERCHANT,

    /**
     * The identity session that confirmed the movement (`P13-TSK-014`) - one login of one person, by the session's id
     * (never its token): a session confirming many checkouts is card testing's tell. Only the checkout payment seam
     * carries it today ({@code PaymentRiskJudgement}, from the request's own authenticated session).
     */
    SESSION;

    /** A canonical {@code UUID.toString()}. */
    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /**
     * The shape of a subject reference of this dimension - a device's hash, every other dimension a canonical UUID; the
     * same rule as {@code risk V005}'s {@code velocity_window_subject_ref_shape}, so no window is keyed on a device
     * description or an arbitrary string.
     */
    public Pattern referenceShape() {
        return this == DEVICE ? DeviceHash.SHAPE : UUID_SHAPE;
    }
}
