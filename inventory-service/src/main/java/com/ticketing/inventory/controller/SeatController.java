package com.ticketing.inventory.controller;

import com.ticketing.inventory.dto.SeatHoldResponse;
import com.ticketing.inventory.dto.SeatRequest;
import com.ticketing.inventory.dto.SeatResponse;
import com.ticketing.inventory.service.SeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<SeatHoldResponse> hold(@PathVariable("seatId") Long seatId) {
        Long bookingId = 5L;
        return ResponseEntity.ok(seatService.holdSeat(seatId, bookingId));
    }
}
