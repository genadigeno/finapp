package com.finapp.app.surface;

import com.finapp.app.security.DatabaseEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires the surface marker's reader and its meter (`P15-TSK-042`). */
@Slf4j
@Configuration
class SurfaceBeans {

    /** The edges' network, as the overlays name it ({@code FINAPP_EDGE_PROXIES}): blank where no edge is in front. */
    static final String EDGE_NETWORK = "server.tomcat.remoteip.internal-proxies";

    @Bean
    RequestSurfaces requestSurfaces(Environment environment) {
        boolean nothingDeployed = nothingDeployed(environment);
        if (nothingDeployed) {
            log.info("No edge network is named and the database is on loopback: this port serves both surfaces, and a request"
                    + " without the edge's {} is not refused to staff for lacking one", RequestSurfaces.HEADER);
        } else {
            log.info("An edge network is named, or the database is off loopback: a request without the edge's {} is the public"
                    + " surface's, and staff are refused at its doors", RequestSurfaces.HEADER);
        }
        return new RequestSurfaces(nothingDeployed);
    }

    /**
     * Whether nothing is deployed in front of this instance, so its own port is both surfaces: <strong>both</strong> no edge network
     * named ({@value #EDGE_NETWORK}, from {@code FINAPP_EDGE_PROXIES}, which every Kubernetes overlay sets and nothing else does) and the
     * database entirely on loopback (the switch of every marked local default, ADR-0019, ADR-0020; an address nobody could read is not
     * loopback).
     *
     * <p>Either alone fails open somewhere. The database's address alone would read a deployment whose database is reached through a
     * sidecar on 127.0.0.1 - a cloud SQL proxy, a PgBouncer - as a laptop, and serve staff on an unmarked request. The edge network
     * alone is one variable an overlay could forget; forgetting it is refused by the edge's policy (`[edge-proxies]`), and a
     * database off loopback still marks the instance as deployed.
     */
    static boolean nothingDeployed(Environment environment) {
        String edgeNetwork = environment.getProperty(EDGE_NETWORK, "");
        return edgeNetwork.isBlank() && DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
    }

    @Bean
    SurfaceRefusalMeters surfaceRefusalMeters(MeterRegistry registry) {
        return new SurfaceRefusalMeters(registry);
    }
}
