package com.ticketing.catalog.controller;

import com.ticketing.catalog.dto.EventRequest;
import com.ticketing.catalog.dto.EventResponse;
import com.ticketing.catalog.dto.EventStatusRequest;
import com.ticketing.catalog.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.createEvent(request));
    }

    @PatchMapping
    public ResponseEntity<EventResponse> changeEventStatus(@Valid @RequestBody EventStatusRequest request) {
        return ResponseEntity.status(HttpStatus.OK).body(eventService.changeEventStatus(request));
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> getPublished() {
        return ResponseEntity.ok(eventService.getPublishedEvents());
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(eventService.getEventById(id));
    }
}
