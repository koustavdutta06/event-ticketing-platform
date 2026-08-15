package com.ticketing.inventory.service;

import com.ticketing.events.SeatHoldExpiredEvent;
import com.ticketing.inventory.entities.Seat;
import com.ticketing.inventory.enums.SeatStatus;
import com.ticketing.inventory.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatExpiryScheduler {

    private static final String TOPIC = "seat-events";

    private final SeatRepository seatRepository;
    private final SeatEventPublisher  seatEventPublisher ;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Note: this requires a heldAt/expiresAt column on Seat itself, or a small
    // separate "SeatHold" tracking table — add a simple `heldUntil` field to Seat for now.
    @Scheduled(fixedRate = 60000) // runs every 60 seconds
    @Transactional
    public void releaseExpiredHolds() {
        List<Seat> expiredSeats = seatRepository
                .findByStatusAndHeldUntilBefore(SeatStatus.HELD, LocalDateTime.now());

        if (expiredSeats.isEmpty()) {
            return;
        }

        log.info("Found {} expired seat hold(s) to release", expiredSeats.size());

        for (Seat seat : expiredSeats) {
            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setHeldUntil(null);
            seatRepository.save(seat);

            log.info("Sending message from releaseExpiredHolds for hold expired seatId {} to topic {}",seat.getId(),TOPIC);
//            kafkaTemplate.send(TOPIC, seat.getId().toString(),
//                    new SeatHoldExpiredEvent(seat.getId(), null));
            seatEventPublisher.publishSeatHoldExpired(
                    new SeatHoldExpiredEvent(seat.getId(), null)
            );

            log.info("Released expired hold on seat {}", seat.getId());
        }
    }
}
