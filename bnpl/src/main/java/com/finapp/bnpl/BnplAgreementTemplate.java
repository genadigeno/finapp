package com.finapp.bnpl;

import java.util.Objects;
import java.util.Optional;

/**
 * The BNPL agreement templates this build registers (`P12-TSK-004`; ADR-0102 decision 4's "Document" group, lending's
 * {@code AgreementTemplate} precedent) - the document a customer is shown and accepts, by id and version. A terms version
 * names one, the offer renders it (`P12-TSK-011`) and the agreement and its acceptance evidence record it (`P12-TSK-013`).
 *
 * <p><strong>Code-registered, never edited.</strong> Changed wording is a new member (a new version of the same id)
 * beside the old one, which stays for as long as an agreement names it. Each member belongs to one product and is
 * checked as the class loads - named for its id and version - so a terms version can never name a document the platform
 * cannot render. The neutral EUR reference jurisdiction (A1) is the template's own: a jurisdiction is a document, not a
 * terms field (lending's reading, kept).
 */
public enum BnplAgreementTemplate {

    /** Pay-in-4's agreement, version 1 - the neutral EUR reference jurisdiction (A1). */
    BNPL_PAY_IN_4_AGREEMENT_V1(BnplProduct.BNPL_PAY_IN_4, "BNPL_PAY_IN_4_AGREEMENT", 1),

    /** Pay-in-N's agreement, version 1 - the neutral EUR reference jurisdiction (A1). */
    BNPL_PAY_IN_N_AGREEMENT_V1(BnplProduct.BNPL_PAY_IN_N, "BNPL_PAY_IN_N_AGREEMENT", 1);

    static {
        for (BnplAgreementTemplate template : values()) {
            if (!template.name().equals(template.id + "_V" + template.version)) {
                throw new IllegalStateException(template.name() + ": a member is named for its id and version");
            }
        }
    }

    private final BnplProduct product;
    private final String id;
    private final int version;

    BnplAgreementTemplate(BnplProduct product, String id, int version) {
        this.product = Objects.requireNonNull(product, "product");
        this.id = Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("a template version is numbered from 1");
        }
        this.version = version;
    }

    /** The product whose agreement this is. */
    public BnplProduct product() {
        return product;
    }

    /** The template's id - what a terms version and an agreement record. */
    public String id() {
        return id;
    }

    /** The template's version, from 1. */
    public int version() {
        return version;
    }

    /** The registered template {@code id} at {@code version}, or empty - for the caller to refuse, never to guess. */
    public static Optional<BnplAgreementTemplate> of(String id, int version) {
        for (BnplAgreementTemplate template : values()) {
            if (template.id.equals(id) && template.version == version) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }
}
