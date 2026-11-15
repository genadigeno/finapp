package com.finapp.app.api;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The answer to a request that could not reach the database, from configuration (`P16-REM-002`). */
@Configuration(proxyBeanMethods = false)
public class DatabaseUnreachableBeans {

    @Bean
    DatabaseUnreachableAnswer databaseUnreachableAnswer(
            @Value("${finapp.api.database-unreachable.retry-after:PT5S}") Duration retryAfter,
            @Value("${finapp.api.database-unreachable.retry-after-spread:PT5S}") Duration spread) {
        return new DatabaseUnreachableAnswer(retryAfter, spread);
    }
}
