package com.finapp.app.lifecycle;

/**
 * Whether this instance may do background work now (`P15-TSK-005`): the one seam by which schedules and consumers wait for
 * the schema this build expects, as readiness keeps HTTP from it. Process-local, like what it gates.
 */
public interface WorkHold {

    /** A hold that is never on: what a schedule or a consumer built outside the application context has. */
    WorkHold NONE = new WorkHold() {
        @Override
        public boolean held() {
            return false;
        }

        @Override
        public void whenHeld(Runnable onHeld) {
            // Never on, so never told.
        }
    };

    /** @return whether to do no work now; answers at once and never waits on the database */
    boolean held();

    /** @param onHeld told each time the hold comes on over work already running; must not block */
    void whenHeld(Runnable onHeld);
}
