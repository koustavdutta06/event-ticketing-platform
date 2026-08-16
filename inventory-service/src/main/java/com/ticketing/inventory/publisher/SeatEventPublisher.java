package com.ticketing.inventory.publisher;

import com.ticketing.events.SeatHeldEvent;
import com.ticketing.events.SeatHoldExpiredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatEventPublisher {

    private static final String TOPIC = "seat-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishSeatHeld(SeatHeldEvent event) {
        // key = seatId as string — ensures all events for the same seat go to the same partition,
        // preserving per-seat ordering (critical: we never want a HoldExpired to be processed
        // before its corresponding SeatHeld for the same seat)
        kafkaTemplate.send(TOPIC, event.seatId().toString(), event);
    }

    public void publishSeatHoldExpired(SeatHoldExpiredEvent event) {
        kafkaTemplate.send(TOPIC, event.seatId().toString(), event);
    }
}