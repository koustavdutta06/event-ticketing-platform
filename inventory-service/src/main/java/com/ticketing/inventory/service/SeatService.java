package com.ticketing.inventory.service;

import com.ticketing.events.SeatHeldEvent;
import com.ticketing.inventory.dto.SeatHoldResponse;
import com.ticketing.inventory.dto.SeatRequest;
import com.ticketing.inventory.dto.SeatResponse;
import com.ticketing.inventory.entities.Seat;
import com.ticketing.inventory.enums.SeatStatus;
import com.ticketing.inventory.publisher.SeatEventPublisher;
import com.ticketing.inventory.repository.SeatRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatService {

    private final SeatRepository seatRepository;
    private final SeatEventPublisher seatEventPublisher;

    public SeatResponse createSeat(SeatRequest request) {
        Seat seat = Seat.builder()
                .eventId(request.eventId())
                .seatNumber(request.seatNumber())
                .seatSection(request.seatSection())
                .price(request.price())
                .status(SeatStatus.AVAILABLE)
                .build();

        Seat saved = seatRepository.save(seat);
        return toResponse(saved);
    }

    public List<SeatResponse> getSeatsForEvent(Long eventId) {
        return seatRepository.findByEventId(eventId).stream().map(this::toResponse).toList();
    }

//    @Transactional
//    public SeatHoldResponse holdSeat(Long seatId) {
//        Seat seat = seatRepository.findById(seatId)
//                .orElseThrow(() -> new EntityNotFoundException("Seat not found: " + seatId));
//
//        if (seat.getStatus() != SeatStatus.AVAILABLE) {
//            return new SeatHoldResponse(seatId, seat.getStatus(), false);
//        }
//
//        seat.setStatus(SeatStatus.HELD);
//        // save() here triggers the @Version check — if another transaction modified
//        // this row since we read it, Hibernate throws OptimisticLockException instead
//        // of silently overwriting a concurrent hold.
//        seatRepository.save(seat);
//
//        return new SeatHoldResponse(seatId, SeatStatus.HELD, true);
//    }

    @Transactional
    public SeatHoldResponse holdSeat(Long seatId, Long bookingId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new EntityNotFoundException("Seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            return new SeatHoldResponse(seatId, seat.getStatus(), false);
        }

        seat.setStatus(SeatStatus.HELD);
        seat.setHeldUntil(LocalDateTime.now().plusMinutes(1));
        seatRepository.save(seat);

        LocalDateTime now = LocalDateTime.now();
        log.info("Sending message from holdSeat for held seatId {}",seat.getId());
        seatEventPublisher.publishSeatHeld(
                new SeatHeldEvent(seatId, seat.getEventId(), bookingId, now, now.plusMinutes(1))
        );

        return new SeatHoldResponse(seatId, SeatStatus.HELD, true);
    }

    private SeatResponse toResponse(Seat s) {
        return new SeatResponse(s.getId(), s.getEventId(), s.getSeatNumber(), s.getSeatSection(), s.getPrice(), s.getStatus());
    }
}
