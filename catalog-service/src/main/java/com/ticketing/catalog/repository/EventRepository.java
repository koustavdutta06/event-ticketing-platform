package com.ticketing.catalog.repository;

import com.ticketing.catalog.entities.Event;
import com.ticketing.catalog.enums.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByStatus(EventStatus status);
}
