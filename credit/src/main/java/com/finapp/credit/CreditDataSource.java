package com.finapp.credit;

import java.util.Set;

/**
 * A credit data source (`P10-TSK-007`; ADR-0085 section 2, ADR-0008's port shape) - what every kind of provider the
 * decision reads has in common: a code, a source kind, the attributes its answer normalises to, and a pull that
 * answers inside the closed {@link CreditDataAnswer}.
 *
 * <p><strong>Verdicts, never vocabulary</strong> ({@code INV-PAY-03}'s rule, applied to credit data): no provider
 * status, path or field crosses this port. An adapter normalises its wire totally and never throws for a provider
 * fault; whatever it does not understand is {@code Unavailable}, which carries no attribute, so a faulty source can
 * never approve anything ({@code INV-CRD-10}). Our reference is the provider's idempotency key.
 *
 * <p>{@link CreditBureau} and {@link FinancialDataProvider} are its kinds; {@link CreditDataCollection} collects from
 * either on one machinery, gated on the kind's own consent purpose ({@code INV-CRD-03}).
 */
public interface CreditDataSource {

    /** The source's code - its declaration's, its evidence's and its meter's. */
    String code();

    /** Which kind of source this is - the kind its data requests carry and its consent purpose is read for. */
    CreditSourceKind kind();

    /** The attribute codes this source's answer normalises to - each present, or stated absent. */
    Set<CreditAttributeCode> attributes();

    /** Pulls the subject's data under our reference. Never throws for a provider fault. */
    CreditDataAnswer pull(CreditDataPull request);
}
