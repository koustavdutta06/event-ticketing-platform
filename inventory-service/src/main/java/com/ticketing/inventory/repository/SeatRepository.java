package com.ticketing.inventory.repository;

import com.ticketing.inventory.entities.Seat;
import com.ticketing.inventory.enums.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByEventId(Long eventId);

    List<Seat> findByStatusAndHeldUntilBefore(SeatStatus seatStatus, LocalDateTime now);
}
