package com.finapp.platform.sourcejoin;

import java.util.List;

/**
 * A posting module's read port for the source-join census (`P15-TSK-035`): every namespace the module writes, joined to the
 * records that cause its entries. Over the module's own tables only.
 */
public interface JournalSources {

    /** The module, its schema's name: what {@code SourceJoinCoverageTest} holds each join's statement to. */
    String module();

    /** One join per namespace this module writes. */
    List<SourceJoin> joins();
}
