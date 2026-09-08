package com.ticketing.catalog.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Kubernetes probes hit /actuator/health every few seconds and Prometheus scrapes
 * /actuator/prometheus every 15s. Without this, both flood Zipkin's trace list and the
 * http_server_requests_seconds metrics with pure noise, drowning out real traffic.
 * Suppressing the Observation itself (not just filtering the Zipkin UI) means no span
 * or metric is created for these calls at all.
 */
@Configuration
public class ObservationConfig {

    @Bean
    public ObservationPredicate noActuatorObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                return !serverContext.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
