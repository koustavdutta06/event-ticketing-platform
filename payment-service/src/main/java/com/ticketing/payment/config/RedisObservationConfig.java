package com.ticketing.payment.config;

import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The actuator Redis health indicator issues raw commands (INFO, CLIENT, HELLO, ...)
 * outside of any traced request — its own /actuator/health observation is suppressed
 * (see ObservationConfig), so these Redis commands have no real parent trace and would
 * otherwise show up as orphaned root traces in Zipkin. Real business Redis calls always
 * run inside an active traced HTTP request, so checking for a real current observation
 * (rather than matching specific command names one at a time) cleanly separates the two.
 */
@Configuration
public class RedisObservationConfig {

    @Bean
    public ObservationPredicate noUnparentedRedisObservations(ObjectProvider<ObservationRegistry> registryProvider) {
        // ObjectProvider defers the lookup until the predicate actually runs — injecting
        // ObservationRegistry directly here creates a circular dependency, since Boot
        // builds the registry itself from all ObservationPredicate beans.
        return (name, context) -> {
            if (context instanceof LettuceObservationContext) {
                Observation current = registryProvider.getObject().getCurrentObservation();
                return current != null && !current.isNoop();
            }
            return true;
        };
    }
}
