package com.finapp.accounting;

import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SourceNamespace;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every module's source joins, one per namespace (`P15-TSK-035`). A namespace claimed twice fails the construction, and so the
 * application's start: two joins for one namespace would make "which record?" depend on list order.
 */
public final class SourceJoinCatalogue {

    private final Map<SourceNamespace, SourceJoin> joins;

    public SourceJoinCatalogue(List<JournalSources> sources) {
        Map<SourceNamespace, SourceJoin> byNamespace = new HashMap<>();
        for (JournalSources module : Objects.requireNonNull(sources, "sources")) {
            for (SourceJoin join : module.joins()) {
                if (byNamespace.putIfAbsent(join.namespace(), join) != null) {
                    throw new IllegalArgumentException("two source joins claim " + join.namespace());
                }
            }
        }
        this.joins = Map.copyOf(byNamespace);
    }

    /** The join answering for this namespace, if any. */
    public Optional<SourceJoin> join(SourceNamespace namespace) {
        return Optional.ofNullable(joins.get(namespace));
    }

    /** Every join in this catalogue. */
    public Collection<SourceJoin> joins() {
        return joins.values();
    }
}
