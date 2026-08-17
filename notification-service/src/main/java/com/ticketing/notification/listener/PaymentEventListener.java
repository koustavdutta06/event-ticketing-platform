package com.ticketing.notification.listener;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.events.PaymentFailedEvent;
import com.ticketing.events.PaymentSucceededEvent;
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
public class PaymentEventListener {

    private ObjectMapper objectMapper;

    @KafkaListener(topics = "payment-events", groupId = "notification-service")
    public void handleSeatEvent(ConsumerRecord<String, Object> consumerRecord) {
        System.out.println("RECEIVED EVENT: " + consumerRecord + " | actual class: " + consumerRecord.getClass().getName());
        if (consumerRecord.value() instanceof PaymentSucceededEvent paymentSucceededEvent) {
            log.info("Payment succeeded for seat {}", paymentSucceededEvent.seatId());
        } else if (consumerRecord.value() instanceof PaymentFailedEvent paymentFailedEvent) {
            log.info("Payment failed for seat {}", paymentFailedEvent.seatId());
        } else {
            log.error("Unrecognized event type: {}", consumerRecord.value().getClass());
        }
    }
}
