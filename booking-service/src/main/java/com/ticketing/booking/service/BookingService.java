package com.ticketing.booking.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final InventoryClient inventoryClient;
    private final CatalogClient catalogClient;

    public Mono<String> initiateBooking(Long seatId, Long eventId) {

        return catalogClient.getEvent(eventId)
                .flatMap(event -> {
                    if (!"PUBLISHED".equals(event.status())) {
                        return Mono.just("Event is not open for booking");
                    }
                    return inventoryClient.holdSeat(seatId)
                            .map(result -> result.success()
                                    ? "Seat held for " + event.name() + " - proceed to payment"
                                    : "Seat unavailable");
                });
    }
}