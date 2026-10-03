package com.finapp.payments;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * The recording double for {@link SettlementExpectations} (`P8-TSK-004`, ADR-0067 §1):
 * production ships no do-nothing implementation — a completion that opens nothing loses
 * track of money — so a test asserting payment semantics takes this instead, and a test
 * asserting the seam reads back what crossed it.
 */
final class RecordingSettlementExpectations implements SettlementExpectations {

    final List<Opening> openings = new ArrayList<>();
    final List<AliasRegistration> aliases = new ArrayList<>();
    final List<ParkedValue> parkings = new ArrayList<>();

    @Override
    public void open(Connection unitOfWork, Opening opening) {
        openings.add(opening);
    }

    @Override
    public void alias(Connection unitOfWork, AliasRegistration registration) {
        aliases.add(registration);
    }

    @Override
    public void parked(Connection unitOfWork, ParkedValue parked) {
        parkings.add(parked);
    }
}
