package com.ticketing.notification.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.SeatHeldEvent;
import com.ticketing.events.SeatHoldExpiredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SeatEventListener {

    private ObjectMapper objectMapper;

    @KafkaListener(topics = "seat-events", groupId = "notification-service")
    public void handleSeatEvent(ConsumerRecord<String, Object> consumerRecord) {
        System.out.println("RECEIVED EVENT: " + consumerRecord + " | actual class: " + consumerRecord.getClass().getName());
        if (consumerRecord.value() instanceof SeatHeldEvent held) {
            log.info("Seat {} held for booking {}, expires at {}", held.seatId(), held.bookingId(), held.expiresAt());
        } else if (consumerRecord.value() instanceof SeatHoldExpiredEvent expired) {
            log.info("Seat {} hold expired, released back to available", expired.seatId());
        } else {
            log.error("Unrecognized event type: {}", consumerRecord.value().getClass());
        }
    }


}