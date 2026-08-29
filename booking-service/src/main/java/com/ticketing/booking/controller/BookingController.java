package com.ticketing.booking.controller;

import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.PaymentOrderResponse;
import com.ticketing.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public Mono<ResponseEntity<BookingResponse>> book(@RequestParam("seatId") Long seatId, @RequestParam("eventId") Long eventId) {
        return bookingService.initiateBooking(seatId, eventId).map(ResponseEntity::ok);
    }

    @PostMapping("/{bookingId}/create-payment-order")
    public Mono<ResponseEntity<PaymentOrderResponse>> createPaymentOrder(@PathVariable("bookingId") Long bookingId) {
        return bookingService.createPaymentOrder(bookingId).map(ResponseEntity::ok);
    }
}