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

    /**
     * Where the database is entirely on loopback nothing is deployed in front of this instance, so its port is both surfaces; anywhere
     * else an edge must have marked every request, and a request it did not mark refuses staff. The same switch as every marked local
     * default (ADR-0019, ADR-0020): an address nobody could read is not loopback.
     */
    @Bean
    RequestSurfaces requestSurfaces(Environment environment) {
        boolean nothingDeployed = DatabaseEndpoint.isEntirelyLoopback(DatabaseEndpoint.url(environment));
        if (nothingDeployed) {
            log.info("The database is on loopback: this port serves both surfaces, and a request without the edge's {} is not refused"
                    + " to staff for lacking one", RequestSurfaces.HEADER);
        } else {
            log.info("Behind the edge: a request without the edge's {} is the public surface's, and staff are refused at its doors",
                    RequestSurfaces.HEADER);
        }
        return new RequestSurfaces(nothingDeployed);
    }

    @Bean
    SurfaceRefusalMeters surfaceRefusalMeters(MeterRegistry registry) {
        return new SurfaceRefusalMeters(registry);
    }
}
