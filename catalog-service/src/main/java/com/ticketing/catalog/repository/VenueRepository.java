package com.ticketing.catalog.repository;

import com.ticketing.catalog.entities.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {
}
