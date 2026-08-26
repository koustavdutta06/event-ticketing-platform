package com.ticketing.payment.service;

import com.ticketing.events.PaymentFailedEvent;
import com.ticketing.events.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OrderTrackingService orderTrackingService;

    public void processPaymentWebhook(String payload) {
        JSONObject json = new JSONObject(payload);
        String event = json.getString("event");

        log.info("Processing Razorpay webhook event: {}", event);

        switch (event) {
            case "payment.captured" -> handlePaymentCaptured(json);
            case "payment.failed"   -> handlePaymentFailed(json);
            default -> log.info("Unhandled Razorpay event: {}", event);
        }
    }

    private void handlePaymentCaptured(JSONObject json) {
        JSONObject payment = extractPaymentEntity(json);

        String razorpayPaymentId = payment.getString("id");
        String razorpayOrderId   = payment.getString("order_id");
        BigDecimal amount = BigDecimal.valueOf(payment.getLong("amount"))
                .divide(BigDecimal.valueOf(100)); // paise → rupees

        Long bookingId = orderTrackingService.getBookingId(razorpayOrderId);
        Long seatId    = orderTrackingService.getSeatId(razorpayOrderId);

        if (bookingId == null || seatId == null) {
            log.error("No tracking entry found for Razorpay order: {}", razorpayOrderId);
            return;
        }

        kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, bookingId.toString(),
                new PaymentSucceededEvent(bookingId, seatId, amount, razorpayPaymentId));

        log.info("PaymentSucceededEvent published — booking: {}, payment: {}",
                bookingId, razorpayPaymentId);
//        orderTrackingService.remove(razorpayOrderId);
    }

    private void handlePaymentFailed(JSONObject json) {
        JSONObject payment = extractPaymentEntity(json);

        String razorpayOrderId   = payment.getString("order_id");
        String errorDescription  = payment.optString("error_description", "Payment failed");

        Long bookingId = orderTrackingService.getBookingId(razorpayOrderId);
        Long seatId    = orderTrackingService.getSeatId(razorpayOrderId);

        if (bookingId == null || seatId == null) {
            log.error("No tracking entry found for Razorpay order: {}", razorpayOrderId);
            return;
        }

        kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, bookingId.toString(),
                new PaymentFailedEvent(bookingId, seatId, errorDescription));

        log.info("PaymentFailedEvent published — booking: {}, reason: {}",
                bookingId, errorDescription);
//        orderTrackingService.remove(razorpayOrderId);
    }

    private JSONObject extractPaymentEntity(JSONObject json) {
        return json.getJSONObject("payload")
                .getJSONObject("payment")
                .getJSONObject("entity");
    }
}