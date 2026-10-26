package com.finapp.app.kyc;

import com.finapp.kyc.CheckId;

/**
 * Told after kyc records a {@code RESCREEN} check's {@code HIT} (`P13-TSK-022`'s last review, item 3): the permit that
 * asked the question - risk's rescreening, {@code app}'s {@code RescreeningHits} - is told, so a hit that lands after
 * the permit concluded otherwise (a late callback for an abandoned question, a slow call that outlived its lease) still
 * reaches a person. kyc's side of the seam: {@link VerificationRunService#rescreen} and the provider's callback call it
 * after their commit, on the run's path, the late callback's and every re-ask or duplicate delivery alike - so it must
 * be idempotent, and it is (the permit's signal and a late one are each born once on their source).
 *
 * <p>An interface here, implemented in {@code app}'s risk wiring and reached through an {@code ObjectProvider}, so kyc's
 * services depend on no risk type - and where no rescreening is wired nothing is told.
 */
@FunctionalInterface
public interface RescreenHits {

    /** Nothing is told - no rescreening wired. */
    RescreenHits NONE = checkId -> { };

    /** The {@code RESCREEN} check {@code checkId} answered {@code HIT}, committed. Idempotent. */
    void hit(CheckId checkId);
}
