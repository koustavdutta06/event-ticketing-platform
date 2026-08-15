package com.ticketing.booking.service;

import com.ticketing.booking.dto.SeatHoldResult;
import com.ticketing.booking.exception.SeatAlreadyHeldException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class InventoryClient {

    private final WebClient inventoryWebClient; // matches the @Bean method name — Spring autowires by name here

    public InventoryClient(@Qualifier("inventoryWebClient") WebClient inventoryWebClient) {
        this.inventoryWebClient = inventoryWebClient;
    }

    public Mono<SeatHoldResult> holdSeat(Long seatId) {
        return inventoryWebClient.post()
                .uri("/api/v1/seats/{seatId}/hold", seatId)
                .retrieve()
                .onStatus(status -> status.value() == 409,
                        response -> Mono.error(new SeatAlreadyHeldException("Seat " + seatId + " is no longer available")))
                .bodyToMono(SeatHoldResult.class);
    }
}