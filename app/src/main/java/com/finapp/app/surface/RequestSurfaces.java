package com.finapp.app.surface;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Reads which surface served a request from the edge's marker (`P15-TSK-042`; {@code THREAT_MODEL.md} T3, T31).
 *
 * <p><strong>Exactly one value, exactly one of two words, or it is not a marker.</strong> The edges set {@value #HEADER} with
 * {@code Header.Set}, which replaces every value a client sent, so a request through either edge carries one value and it is
 * {@code public} or {@code operator}. Anything else - absent, empty, two values, {@code Operator}, {@code operator, public} -
 * did not come from an edge as built, and is read as no marker at all: {@link Surface#UNMARKED} where an edge must be in front,
 * {@link Surface#SINGLE_PORT} where nothing is deployed. An unusable marker can never read as the operator surface.
 *
 * <p>The header's name is matched as Tomcat matches every name, ignoring case and nothing else: {@code X_Finapp_Surface} is a
 * different header, never read here, whatever a proxy would make of it.
 *
 * <p>Immutable, built once from the environment, the same on every replica: there is no state to share.
 */
public final class RequestSurfaces {

    /** The edges' marker. {@code EdgeManifestsTest} holds both edges' files to this name and to {@link Surface#tag()}'s words. */
    public static final String HEADER = "X-Finapp-Surface";

    private final boolean nothingDeployed;

    /**
     * @param nothingDeployed whether this instance runs where nothing is deployed in front of it - the database entirely on
     *     loopback, {@code DatabaseEndpoint.isEntirelyLoopback} - so its own port is both surfaces. False everywhere else, and
     *     false for a database address nobody could read
     */
    public RequestSurfaces(boolean nothingDeployed) {
        this.nothingDeployed = nothingDeployed;
    }

    /** The surface that served {@code request}, never {@code null}. */
    public Surface of(HttpServletRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Enumeration<String> values = request.getHeaders(HEADER);
        String only = null;
        int count = 0;
        while (values != null && values.hasMoreElements()) {
            only = values.nextElement();
            count++;
        }
        if (count == 1) {
            if (Surface.PUBLIC.tag().equals(only)) {
                return Surface.PUBLIC;
            }
            if (Surface.OPERATOR.tag().equals(only)) {
                return Surface.OPERATOR;
            }
        }
        return nothingDeployed ? Surface.SINGLE_PORT : Surface.UNMARKED;
    }

    /** Whether a request with no usable marker is the single port here, for the startup line and the tests. */
    public boolean nothingDeployed() {
        return nothingDeployed;
    }
}
