package com.ticketing.booking.repository;

import com.ticketing.booking.entities.Booking;
import com.ticketing.booking.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findBySeatIdAndStatus(Long seatId, BookingStatus status);
}