package com.ticketing.booking.service;

import com.ticketing.booking.client.CatalogClient;
import com.ticketing.booking.client.InventoryClient;
import com.ticketing.booking.client.PaymentClient;
import com.ticketing.booking.entities.Booking;
import com.ticketing.booking.enums.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.PaymentOrderResponse;
import com.ticketing.booking.exception.BookingNotFoundException;
import com.ticketing.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final InventoryClient inventoryClient;
    private final CatalogClient catalogClient;
    private final PaymentClient paymentClient;
    private final BookingRepository bookingRepository;

    public Mono<BookingResponse> initiateBooking(Long seatId, Long eventId) {
        log.info("Initiating booking with seatId {} and eventId {}",seatId, eventId);
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> (String) ctx.getAuthentication().getPrincipal())
            .flatMap(customerEmail ->
                Mono.fromFuture(() -> catalogClient.getEvent(eventId))
                .flatMap(event -> {
                    log.info("Recieved event : {}", event);
                    if (!"PUBLISHED".equals(event.status())) {
                            return Mono.just(new BookingResponse(
                                null, "REJECTED",
                                "Event is not open for booking — " + event.status(),
                                null));
                    }

                    LocalDateTime now = LocalDateTime.now();
                    Booking booking = Booking.builder()
                            .seatId(seatId)
                            .eventId(eventId)
                            .customerEmail("koustavdutta06@gmail.com")
                            .status(BookingStatus.PENDING_PAYMENT)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    Booking savedBooking = bookingRepository.save(booking);

                    log.info("saved booking and calling inventory service for holding seat {} for event {}", seatId, eventId);
                    return Mono.fromFuture(() -> inventoryClient.holdSeat(seatId, savedBooking.getId(), eventId))
                        .map(result -> {
                            log.info("Recieved details from inventory service : {}", result);
                            if (result.success()){
                                savedBooking.setAmount(result.price());
                                savedBooking.setUpdatedAt(LocalDateTime.now());
                                bookingRepository.save(savedBooking);
                                return new BookingResponse(
                                    savedBooking.getId(), "PENDING_PAYMENT",
                                    "Seat held, proceed to payment", result.holdExpiresAt());
                            } else {
                                // Compensating action: seat hold failed, mark our own booking as cancelled
                                savedBooking.setStatus(BookingStatus.CANCELLED);
                                savedBooking.setUpdatedAt(LocalDateTime.now());
                                bookingRepository.save(savedBooking);
                                String message = switch (result.status()) {
                                    case "EVENT_MISMATCH" -> "Seat does not belong to the specified event";
                                    case "ALREADY_HELD", "HELD", "BOOKED" -> "Seat is no longer available";
                                    default -> "Seat unavailable";
                                };
                                return new BookingResponse(savedBooking.getId(), "CANCELLED", message, null);
                            }
                        });
                    })
            );
    }

    public Mono<PaymentOrderResponse> createPaymentOrder(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found: " + bookingId));
        return Mono.fromFuture(() ->
                paymentClient.createPaymentOrder(booking.getId(), booking.getSeatId(), booking.getAmount()));
    }
}