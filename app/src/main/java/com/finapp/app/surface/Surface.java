package com.finapp.app.surface;

/**
 * Which edge served a request (`P15-TSK-042`; {@code THREAT_MODEL.md} T1, T3, T31).
 *
 * <p>The application cannot see this for itself: both edges forward to the same Service, and a request's own headers are the
 * client's. So each edge states it in {@link RequestSurfaces#HEADER}, set by the controller and replacing whatever the client
 * sent, and the application believes it because no other pod can reach its port: {@code finapp-app-ingress}
 * ({@code deploy/base/networkpolicy-app.yaml}) admits the two controllers to 8080 and nothing else, held by
 * {@code NamespaceSecurityManifestsTest}. <strong>One exception the policy itself names</strong>: a pod cannot block its own node,
 * and on EKS the network policy agent admits the node's address on every port, so a host-network process on that node could write
 * the marker. Such a process could reach the operator routes directly as well, so the marker gives it nothing more. Without the
 * policy the marker would be any pod's to write.
 *
 * <p><strong>Two answers for a missing marker, and which one is not a choice a request can make.</strong> Where an edge may be in
 * front - an edge network named ({@code FINAPP_EDGE_PROXIES}), or the database off loopback - a request with no usable marker is
 * {@link #UNMARKED}, refused to staff as the public surface is, and recorded as such so a rollout that forgot the edge is loud.
 * Only where neither holds - a laptop, the test tiers: no edge network and the database on loopback - is the application's own port
 * both surfaces, as it was before the edge existed: {@link #SINGLE_PORT}. The database's address alone would read a deployment
 * reaching its database through a loopback sidecar as a laptop ({@code SurfaceBeans.nothingDeployed}).
 */
public enum Surface {

    /** The public edge's word: the internet. */
    PUBLIC("public"),

    /** The operator edge's word: the internal network. */
    OPERATOR("operator"),

    /** No usable marker where an edge must have set one: treated as the public surface, so staff fail closed. */
    UNMARKED("unmarked"),

    /** No usable marker where nothing is deployed: the one port serves both surfaces, as it did before the edge. */
    SINGLE_PORT("single_port");

    private final String tag;

    Surface(String tag) {
        this.tag = tag;
    }

    /** What the audit record and the meter call it. */
    public String tag() {
        return tag;
    }

    /**
     * Whether a role-holding identity may prove, strengthen or recover its access here.
     *
     * <p>Written as the two that may, so a surface added later is refused until somebody decides otherwise.
     */
    public boolean admitsRoleHolders() {
        return this == OPERATOR || this == SINGLE_PORT;
    }
}
