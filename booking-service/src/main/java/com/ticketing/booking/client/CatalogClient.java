package com.ticketing.booking.client;

import com.ticketing.booking.dto.EventDetails;
import com.ticketing.booking.exception.EventNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class CatalogClient {

    private final WebClient catalogWebClient; // the bean from Step 3 — finally used

    public CatalogClient(@Qualifier("catalogWebClient") WebClient catalogWebClient) {
        this.catalogWebClient = catalogWebClient;
    }

    @CircuitBreaker(name = "catalogService", fallbackMethod = "getEventFallback")
    @Retry(name = "catalogService")
    @TimeLimiter(name = "catalogService")
    public CompletableFuture<EventDetails> getEvent(Long eventId) {
        return catalogWebClient.get()
                .uri("/api/v1/events/{eventId}", eventId)
                .retrieve()
                .onStatus(status -> status.value() == 404,
                response -> Mono.error(new EventNotFoundException(
                    "Event not found: " + eventId)))
            .bodyToMono(EventDetails.class)
            .toFuture();
    }

    public CompletableFuture<EventDetails> getEventFallback(Long eventId, Throwable throwable) {
        log.error("catalog-service circuit open or retries exhausted for event {}: {}",
            eventId, throwable.getMessage());
        return CompletableFuture.completedFuture(
            new EventDetails(eventId, "UNAVAILABLE", "UNAVAILABLE",
                LocalDateTime.now(), "UNAVAILABLE"));
    }
}