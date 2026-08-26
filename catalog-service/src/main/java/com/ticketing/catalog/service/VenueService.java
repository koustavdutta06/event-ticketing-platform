package com.ticketing.catalog.service;

import com.ticketing.catalog.entities.Venue;
import com.ticketing.catalog.dto.VenueRequest;
import com.ticketing.catalog.dto.VenueResponse;
import com.ticketing.catalog.repository.VenueRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    @CacheEvict(value = "venues", allEntries = true)
    public VenueResponse createVenue(VenueRequest request) {
        Venue venue = Venue.builder()
                .name(request.name())
                .city(request.city())
                .totalCapacity(request.totalCapacity())
                .build();

        Venue saved = venueRepository.save(venue);
        return new VenueResponse(saved.getId(), saved.getName(), saved.getCity(), saved.getTotalCapacity());
    }

    @Cacheable(value = "venues", key = "#id")
    public VenueResponse getVenueById(Long id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Venue not found: " + id));
        return new VenueResponse(venue.getId(), venue.getName(),
                venue.getCity(), venue.getTotalCapacity());
    }

    @Cacheable(value = "venues", key = "'all'")
    public List<VenueResponse> getAllVenues() {
        return venueRepository.findAll().stream()
                .map(v -> new VenueResponse(v.getId(), v.getName(),
                        v.getCity(), v.getTotalCapacity()))
                .toList();
    }
}