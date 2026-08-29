package com.ticketing.booking.client;

import com.ticketing.booking.dto.SeatHoldResult;
import com.ticketing.booking.exception.InvalidSeatForEventException;
import com.ticketing.booking.exception.SeatAlreadyHeldException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class InventoryClient {

    private final WebClient inventoryWebClient; // matches the @Bean method name — Spring autowires by name here

    public InventoryClient(@Qualifier("inventoryWebClient") WebClient inventoryWebClient) {
        this.inventoryWebClient = inventoryWebClient;
    }

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "holdSeatFallback")
    @Retry(name = "inventoryService")
    @TimeLimiter(name = "inventoryService")
    public CompletableFuture<SeatHoldResult> holdSeat(Long seatId, Long bookingId, Long eventId) {
        return inventoryWebClient.post()
        .uri("/api/v1/seats/{seatId}/hold?bookingId={bookingId}&eventId={eventId}", seatId, bookingId, eventId)
                .retrieve()
                .onStatus(status -> status.value() == 409,
                        response -> Mono.error(new SeatAlreadyHeldException("Seat " + seatId + " is no longer available")))
                .onStatus(status -> status.value() == 400,
                        response -> Mono.error(new InvalidSeatForEventException(
                                "Seat " + seatId + " does not belong to event " + eventId)))
                .bodyToMono(SeatHoldResult.class)
                .toFuture();
    }

    public CompletableFuture<SeatHoldResult> holdSeatFallback(Long seatId, Long bookingId, Long eventId,
                                                              Throwable throwable) {
        log.error("inventory-service circuit open or retries exhausted for seat {}: {}",
                seatId, throwable.getMessage());
        return CompletableFuture.completedFuture(
                new SeatHoldResult(seatId, "UNAVAILABLE", false, null, null));
    }

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "releaseSeatFallback")
    @Retry(name = "inventoryService")
    @TimeLimiter(name = "inventoryService")
    public CompletableFuture<Void> releaseSeat(Long seatId) {
        return inventoryWebClient.post()
                .uri("/api/v1/seats/{seatId}/release", seatId)
                .retrieve()
                .bodyToMono(Void.class)
                .toFuture();
    }

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "releaseSeatFallback")
    @Retry(name = "inventoryService")
    @TimeLimiter(name = "inventoryService")
    public CompletableFuture<Void> updateSeatStatus(Long seatId, boolean successStatus) {
        return inventoryWebClient.post()
                .uri("/api/v1/seats/{seatId}/status?success={successStatus}", seatId, successStatus)
                .retrieve()
                .bodyToMono(Void.class)
                .toFuture();
    }

    public CompletableFuture<Void> releaseSeatFallback(Long seatId, Throwable throwable) {
        log.error("CRITICAL: failed to release seat {} — manual intervention may be needed: {}",
                seatId, throwable.getMessage());
        return CompletableFuture.completedFuture(null);
    }
}