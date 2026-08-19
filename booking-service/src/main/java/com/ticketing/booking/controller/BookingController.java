package com.ticketing.booking.controller;

import com.ticketing.booking.client.PaymentClient;
import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.dto.PaymentOrderResponse;
import com.ticketing.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final PaymentClient paymentClient;

    @PostMapping
    public Mono<ResponseEntity<BookingResponse>> book(@RequestParam("seatId") Long seatId, @RequestParam("eventId") Long eventId) {
        return bookingService.initiateBooking(seatId, eventId).map(ResponseEntity::ok);
    }

    @PostMapping("/{bookingId}/create-payment-order")
    public Mono<ResponseEntity<PaymentOrderResponse>> createPaymentOrder(
            @PathVariable("bookingId") Long bookingId,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam("seatId") Long seatId) {
        return paymentClient.createPaymentOrder(bookingId, seatId, amount)
                .map(ResponseEntity::ok);
    }
}