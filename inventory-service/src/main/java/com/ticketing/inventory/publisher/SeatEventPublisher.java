package com.ticketing.inventory.publisher;

import com.ticketing.events.SeatHeldEvent;
import com.ticketing.events.SeatHoldExpiredEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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

//    public void publishSeatHeld(SeatHeldEvent event) {
//        send(event.seatId().toString(), event, "seatHeld");
//    }
//
//    public void publishSeatHoldExpired(SeatHoldExpiredEvent event) {
//        send(event.seatId().toString(), event, "seatHoldExpired");
//    }

//    private void send(String key, Object payload, String typeId) {
//        ProducerRecord<String, Object> record = new ProducerRecord<>(TOPIC, key, payload);
//        record.headers().add(new RecordHeader("__TypeId__", typeId.getBytes(StandardCharsets.UTF_8)));
//        kafkaTemplate.send(record);
//    }
}