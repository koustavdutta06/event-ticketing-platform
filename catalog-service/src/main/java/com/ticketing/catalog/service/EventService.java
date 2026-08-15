package com.ticketing.catalog.service;

import com.ticketing.catalog.dto.EventRequest;
import com.ticketing.catalog.dto.EventResponse;
import com.ticketing.catalog.dto.EventStatusRequest;
import com.ticketing.catalog.entities.Event;
import com.ticketing.catalog.entities.Venue;
import com.ticketing.catalog.enums.EventStatus;
import com.ticketing.catalog.repository.EventRepository;
import com.ticketing.catalog.repository.VenueRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;

    public EventResponse createEvent(EventRequest request) {
        Venue venue = venueRepository.findById(request.venueId())
                .orElseThrow(() -> new EntityNotFoundException("Venue not found: " + request.venueId()));

        Event event = Event.builder()
                .name(request.name())
                .venue(venue)
                .startTime(request.startTime())
                .status(EventStatus.DRAFT)
                .build();

        Event saved = eventRepository.save(event);
        return toResponse(saved);
    }

    public List<EventResponse> getPublishedEvents() {
        return eventRepository.findByStatus(EventStatus.PUBLISHED)
                .stream().map(this::toResponse).toList();
    }

    public EventResponse changeEventStatus(EventStatusRequest request) {
        Event event = eventRepository.findById(request.id())
                .orElseThrow(() -> new EntityNotFoundException("Event not found: " + request.id()));
        event.setStatus(request.status());
        eventRepository.save(event);
        return toResponse(event);
    }

    public EventResponse getEventById(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Event not found: " + id));
        return toResponse(event);
    }

    private EventResponse toResponse(Event e) {
        return new EventResponse(e.getId(), e.getName(), e.getVenue().getName(), e.getStartTime(), e.getStatus());
    }
}
