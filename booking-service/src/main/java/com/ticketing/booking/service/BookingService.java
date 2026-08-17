package com.ticketing.booking.service;

import com.ticketing.booking.client.CatalogClient;
import com.ticketing.booking.client.InventoryClient;
import com.ticketing.booking.entities.Booking;
import com.ticketing.booking.enums.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final InventoryClient inventoryClient;
    private final CatalogClient catalogClient;
    private final BookingRepository bookingRepository;

    public Mono<BookingResponse> initiateBooking(Long seatId, Long eventId) {
        return catalogClient.getEvent(eventId)
                .flatMap(event -> {
                    if (!"PUBLISHED".equals(event.status())) {
                        return Mono.just(new BookingResponse(null, "REJECTED", "Event is not open for booking"));
                    }

                    Booking booking = Booking.builder()
                            .seatId(seatId)
                            .eventId(eventId)
                            .status(BookingStatus.PENDING_PAYMENT)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    Booking savedBooking = bookingRepository.save(booking);

                    return inventoryClient.holdSeat(seatId, savedBooking.getId())
                            .map(result -> {
                                if (result.success()){
                                    return new BookingResponse(savedBooking.getId(), "PENDING_PAYMENT",
                                            "Seat held, proceed to payment");
                                } else {
                                    // Compensating action: seat hold failed, mark our own booking as cancelled
                                    savedBooking.setStatus(BookingStatus.CANCELLED);
                                    savedBooking.setUpdatedAt(LocalDateTime.now());
                                    bookingRepository.save(savedBooking);
                                    return new BookingResponse(savedBooking.getId(), "CANCELLED", "Seat unavailable");
                                }
                            });
                });
    }
}