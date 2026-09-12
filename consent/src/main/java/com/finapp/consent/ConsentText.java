package com.finapp.consent;

import java.time.Instant;
import java.util.Objects;

/**
 * One version of one purpose's consent text — the artefact a person agrees to
 * (`P2-TSK-017`, {@code INV-CNS-04}).
 *
 * <p><strong>Immutable by construction and by privilege.</strong> There is no factory that
 * mints a new version here, because the application never writes one: a consent text is a
 * reviewed platform artefact, and it arrives by migration — the application role holds
 * {@code SELECT} alone on {@code consent.consent_text}, so text immutability sits at the
 * strongest rank the platform has. A wording change is a <em>new version in a new
 * migration</em>, whose {@code requiresReconsent} records — as a property of that version,
 * never a guess — whether grants against earlier versions lapse.
 *
 * @param purpose which processing this text is the basis for
 * @param version counts from 1 per purpose; with the purpose, the identity a record pins
 * @param body the words the person was shown, verbatim
 * @param requiresReconsent whether grants against <em>earlier</em> versions lapse now that
 *     this version exists — read by the derivation ({@code ConsentStore#hasCurrentBasis})
 * @param publishedAt when this version took effect
 */
public record ConsentText(
        ConsentPurpose purpose,
        int version,
        String body,
        boolean requiresReconsent,
        Instant publishedAt) {

    public ConsentText {
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("versions count from 1");
        }
        Objects.requireNonNull(body, "body must not be null");
        if (body.isBlank()) {
            throw new IllegalArgumentException(
                    "a consent text with no words is not something anybody agreed to");
        }
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
