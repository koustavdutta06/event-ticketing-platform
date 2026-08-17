package com.ticketing.booking.controller;

import com.ticketing.booking.dto.BookingResponse;
import com.ticketing.booking.publisher.PaymentSimulator;
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
    private final PaymentSimulator paymentSimulator;

    @PostMapping
    public Mono<ResponseEntity<BookingResponse>> book(@RequestParam("seatId") Long seatId, @RequestParam("eventId") Long eventId) {
        return bookingService.initiateBooking(seatId, eventId).map(ResponseEntity::ok);
    }

    @PostMapping("/{bookingId}/pay")
    public ResponseEntity<String> pay(@PathVariable("bookingId") Long bookingId,
                                      @RequestParam("seatId") Long seatId,
                                      @RequestParam("amount") BigDecimal amount) {
//        paymentSimulator.attemptPayment(bookingId, seatId, amount);
        return ResponseEntity.ok(paymentSimulator.attemptPayment(bookingId, seatId, amount));
    }
}