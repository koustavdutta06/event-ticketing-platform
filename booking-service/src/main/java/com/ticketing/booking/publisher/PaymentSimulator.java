package com.ticketing.booking.publisher;

import com.ticketing.events.PaymentFailedEvent;
import com.ticketing.events.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class PaymentSimulator {

    private static final String TOPIC = "payment-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public String attemptPayment(Long bookingId, Long seatId, BigDecimal amount) {
        boolean success = ThreadLocalRandom.current().nextInt(100) < 80; // 80% success rate, for testing both paths

        if (success) {
            kafkaTemplate.send(TOPIC, bookingId.toString(),
                    new PaymentSucceededEvent(bookingId, seatId, amount, "TXN-" + System.currentTimeMillis()));
            return "Payment Success";
        } else {
            kafkaTemplate.send(TOPIC, bookingId.toString(),
                    new PaymentFailedEvent(bookingId, seatId, "Simulated payment decline"));
            return "Payment failed";
        }
    }
}
