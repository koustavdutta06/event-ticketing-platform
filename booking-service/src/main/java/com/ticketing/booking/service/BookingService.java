package com.ticketing.booking.service;

import com.ticketing.booking.client.CatalogClient;
import com.ticketing.booking.client.InventoryClient;
import com.ticketing.booking.client.PaymentClient;
import com.ticketing.booking.entities.Booking;
import com.ticketing.booking.enums.BookingStatus;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.PaymentOrderResponse;
import com.ticketing.booking.exception.BookingNotFoundException;
import com.ticketing.booking.exception.InvalidSeatForEventException;
import com.ticketing.booking.exception.SeatAlreadyHeldException;
import com.ticketing.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final InventoryClient inventoryClient;
    private final CatalogClient catalogClient;
    private final PaymentClient paymentClient;
    private final BookingRepository bookingRepository;

    public Mono<BookingResponse> initiateBooking(Long seatId, Long eventId) {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> (String) ctx.getAuthentication().getPrincipal())
            .flatMap(customerEmail ->
                Mono.fromFuture(() -> catalogClient.getEvent(eventId))
                .flatMap(event -> {
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

                    return Mono.fromFuture(() -> inventoryClient.holdSeat(seatId, savedBooking.getId(), eventId))
                        .map(result -> {
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
                                return new BookingResponse(savedBooking.getId(), "CANCELLED", "Seat unavailable", null);
                            }
                        })
                        .onErrorResume(
                            ex -> ex instanceof SeatAlreadyHeldException || ex instanceof InvalidSeatForEventException,
                            ex -> {
                                // Compensating action: seat hold was rejected, mark our own booking as cancelled
                                savedBooking.setStatus(BookingStatus.CANCELLED);
                                savedBooking.setUpdatedAt(LocalDateTime.now());
                                bookingRepository.save(savedBooking);
                                return Mono.just(new BookingResponse(
                                        savedBooking.getId(), "CANCELLED", ex.getMessage(), null));
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