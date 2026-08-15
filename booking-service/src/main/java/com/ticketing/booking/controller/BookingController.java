package com.ticketing.booking.controller;

import com.ticketing.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public Mono<ResponseEntity<String>> book(@RequestParam("seatId") Long seatId, @RequestParam("eventId") Long eventId) {
        return bookingService.initiateBooking(seatId, eventId).map(ResponseEntity::ok);
    }
}