package com.ticketing.booking.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Surfaces the current trace ID on every response so it can be pasted straight into Zipkin.
 * Uses beforeCommit rather than doOnEach/doFinally so the header is guaranteed to be added
 * before the response is actually sent, regardless of how the downstream chain completes.
 */
@Component
@RequiredArgsConstructor
public class TraceIdHeaderFilter implements WebFilter {

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            Span span = tracer.currentSpan();
            if (span != null) {
                exchange.getResponse().getHeaders().set("X-Trace-Id", span.context().traceId());
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
