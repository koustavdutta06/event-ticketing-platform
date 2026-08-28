package com.ticketing.payment.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.ticketing.payment.dto.PaymentOrderResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final RazorpayClient razorpayClient;
    private final OrderTrackingService orderTrackingService;

    @Value("${razorpay.currency}")
    private String currency;

    @CircuitBreaker(name = "razorpay", fallbackMethod = "createOrderFallback")
    @Retry(name = "razorpay")
    public PaymentOrderResponse createOrder(Long bookingId, Long seatId, BigDecimal amount) {
        try {
            JSONObject options = new JSONObject();
            options.put("amount", amount.multiply(BigDecimal.valueOf(100)).intValue());
            options.put("currency", currency);
            options.put("receipt", "booking_" + bookingId);
            options.put("notes", new JSONObject()
                    .put("bookingId", bookingId)
                    .put("seatId", seatId));

            Order order = razorpayClient.orders.create(options);
            String razorpayOrderId = order.get("id");

            orderTrackingService.track(razorpayOrderId, bookingId, seatId);

            log.info("Razorpay order created: {} for booking: {}", razorpayOrderId, bookingId);

            return new PaymentOrderResponse(
                    razorpayOrderId,
                    bookingId,
                    seatId,
                    amount,
                    currency,
                    order.get("status")
            );
        } catch (RazorpayException e) {
            throw new RuntimeException("Failed to create Razorpay order for booking: " + bookingId, e);
        }
    }

    public PaymentOrderResponse createOrderFallback(Long bookingId, Long seatId,
                                                    BigDecimal amount, Throwable throwable) {
        log.error("Razorpay circuit open or retries exhausted for booking {}: {}",
                bookingId, throwable.getMessage());
        throw new RuntimeException(
            "Payment service temporarily unavailable — please try again shortly", throwable);
    }
}
