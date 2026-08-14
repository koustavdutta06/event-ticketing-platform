package com.ticketing.catalog.service;

import com.ticketing.catalog.entities.Venue;
import com.ticketing.catalog.dto.VenueRequest;
import com.ticketing.catalog.dto.VenueResponse;
import com.ticketing.catalog.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    public VenueResponse createVenue(VenueRequest request) {
        Venue venue = Venue.builder()
                .name(request.name())
                .city(request.city())
                .totalCapacity(request.totalCapacity())
                .build();

        Venue saved = venueRepository.save(venue);
        return new VenueResponse(saved.getId(), saved.getName(), saved.getCity(), saved.getTotalCapacity());
    }
}