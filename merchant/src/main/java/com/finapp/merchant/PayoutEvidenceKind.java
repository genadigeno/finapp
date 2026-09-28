package com.finapp.merchant;

/** What kind of provider bytes a retained payout evidence row holds (`P6-TSK-012`). */
public enum PayoutEvidenceKind {

    /** The answer to a send of our reference — the dispatch's, or a takeover's re-send. */
    RESPONSE,

    /** The answer to the resolution sweep's query by our reference. */
    QUERY_RESULT
}
