package com.ticketing.inventory.controller;

import com.ticketing.inventory.dto.SeatHoldResponse;
import com.ticketing.inventory.dto.SeatRequest;
import com.ticketing.inventory.dto.SeatResponse;
import com.ticketing.inventory.service.SeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @PostMapping
    public ResponseEntity<SeatResponse> create(@Valid @RequestBody SeatRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(seatService.createSeat(request));
    }

    @GetMapping
    public ResponseEntity<List<SeatResponse>> getByEvent(@RequestParam("eventId") Long eventId) {
        return ResponseEntity.ok(seatService.getSeatsForEvent(eventId));
    }

    @PostMapping("/{seatId}/hold")
    public ResponseEntity<SeatHoldResponse> holdSeat(@PathVariable("seatId") Long seatId,
                                                     @RequestParam("bookingId") Long bookingId,
                                                     @RequestParam("eventId") Long eventId) {
        return ResponseEntity.ok(seatService.holdSeat(seatId, bookingId, eventId));
    }

    @PostMapping("/{seatId}/status")
    public ResponseEntity<Void> release(@PathVariable("seatId") Long seatId, @RequestParam("success") boolean success) {
        log.info("Received request for seatId {} and successStatus is {}", seatId, success);
        seatService.changeSeatStatus(seatId, success);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{seatId}/release")
    public ResponseEntity<Void> release(@PathVariable("seatId") Long seatId) {
        seatService.releaseSeat(seatId);
        return ResponseEntity.noContent().build();
    }
}
