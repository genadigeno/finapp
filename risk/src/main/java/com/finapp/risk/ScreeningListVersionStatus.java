package com.finapp.risk;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A screening list version's machine (`P13-TSK-022`; {@code RISK_LIFECYCLES.md} section 4.10; {@code risk V018}'s
 * {@code screening_list_version_permits_only_machine_edges}): {@code RECORDED → ACTIVE → RETIRED} - recorded by one
 * person, activated by a second, retired beside its successor's activation in that transaction. Nothing moves backwards
 * and nothing out of {@code RETIRED}: a list version once superseded stays superseded, and a correction is a new version.
 */
public enum ScreeningListVersionStatus {

    /** Recorded by a person; awaiting a different person's activation. */
    RECORDED,

    /** In force: its activation enqueued the rescreening of every {@code ACTIVE} party. At most one per list. */
    ACTIVE,

    /** Superseded by a later version's activation. Terminal. */
    RETIRED;

    /** The quoted, comma-separated value list the migration's {@code CHECK} uses. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
