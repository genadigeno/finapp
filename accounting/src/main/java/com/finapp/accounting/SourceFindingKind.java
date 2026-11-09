package com.finapp.accounting;

/** What the source-join census found about one journal entry (`P15-TSK-035`). Exactly two: a third would need a gauge and a page. */
public enum SourceFindingKind {
    /** No record behind the entry, or an entry whose namespace no join answers for. */
    UNSOURCED,
    /** A record is behind it, and its figures differ from the entry's lines. */
    MISMATCHED
}
