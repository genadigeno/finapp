package com.finapp.platform.inbox;

import java.io.Serial;

/**
 * A handler refusing a record this build cannot read yet (`P15-TSK-028`): an event version newer than it knows.
 *
 * <p>The record is valid; the build is old. During a rolling update a replica of release N - 1 meets what release N produces, and
 * a deploy that knows the version reads it. So this is never a reason of the record's own: it does not count towards parking
 * ({@link InboxFailures}), and the partition stalls loudly until a build that can read it arrives, as it always did.
 *
 * <p>An {@link IllegalStateException}, the type this refusal was before it needed a name of its own.
 */
public final class EventNotYetReadable extends IllegalStateException {

    @Serial private static final long serialVersionUID = 1L;

    public EventNotYetReadable(String message) {
        super(message);
    }
}
