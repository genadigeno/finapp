package com.finapp.app.crossborder;

import com.finapp.app.session.RequiresSession;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's corridor discovery (`P9-TSK-015`, `PHASE_9_PLAN.md` §9): the active policy's
 * corridors that are available and that a rail this build declares can carry - with the transfer fee,
 * the maximum and the delivery estimate a customer decides by. A read; a session is enough.
 */
@RestController
@RequestMapping(path = "/me/cross-border", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class CorridorDiscoveryController {

    @NonNull private final CorridorAdministrationDesk desk;

    /** The corridors on offer now. */
    @GetMapping("/corridors")
    public CorridorAdministrationDesk.OfferedCorridors listCrossBorderCorridors() {
        return desk.offered();
    }
}
