package com.ticketing.catalog.config;

import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.CurrentTraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4.0.7's tracing autoconfiguration no longer registers an MDC scope
 * decorator on its own, so traceId/spanId never reach the log pattern or the JSON logs
 * shipped to Logstash without this. Picked up automatically by BraveAutoConfiguration's
 * braveCurrentTraceContext bean, which collects all CurrentTraceContext.ScopeDecorator beans.
 */
@Configuration
public class TracingMdcConfig {

    @Bean
    public CurrentTraceContext.ScopeDecorator mdcScopeDecorator() {
        return MDCScopeDecorator.get();
    }
}
