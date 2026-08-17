package com.ticketing.notification.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.BookingCancelledEvent;
import com.ticketing.events.BookingConfirmedEvent;
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
public class BookingEventListener {

    private ObjectMapper objectMapper;

    @KafkaListener(topics = "booking-events", groupId = "notification-service")
    public void handleSeatEvent(ConsumerRecord<String, Object> consumerRecord) {
        System.out.println("RECEIVED EVENT: " + consumerRecord + " | actual class: " + consumerRecord.getClass().getName());
        if (consumerRecord.value() instanceof BookingConfirmedEvent bookingConfirmedEvent) {
            log.info("Booking confirmed for id {} and seat id {} and eventId {}", bookingConfirmedEvent.bookingId(), bookingConfirmedEvent.seatId(), bookingConfirmedEvent.eventId());
        } else if (consumerRecord.value() instanceof BookingCancelledEvent bookingCancelledEvent) {
            log.info("Booking cancelled for id {}", bookingCancelledEvent.bookingId());
        } else {
            log.error("Unrecognized event type: {}", consumerRecord.value().getClass());
        }
    }
}
