package com.ticketing.notification.listener;

import com.ticketing.events.SeatHeldEvent;
import com.ticketing.events.SeatHoldExpiredEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SeatEventListener {

    @KafkaListener(topics = "seat-events", groupId = "notification-service")
    public void handleSeatEvent(Object event) {
        if (event instanceof SeatHeldEvent held) {
            log.info("Seat {} held for booking {}, expires at {}", held.seatId(), held.bookingId(), held.expiresAt());
        } else if (event instanceof SeatHoldExpiredEvent expired) {
            log.info("Seat {} hold expired, released back to available", expired.seatId());
        } else {
            log.error("Unrecognized event type: {}", event.getClass());
        }
    }


}