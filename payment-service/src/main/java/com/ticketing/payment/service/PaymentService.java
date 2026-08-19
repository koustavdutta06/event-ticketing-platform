package com.ticketing.payment.service;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.ticketing.payment.dto.PaymentOrderResponse;
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
            log.info("Razorpay order created: {} for booking: {}", order.get("id"), bookingId);
            orderTrackingService.track(order.get("id"),bookingId,seatId);
            return new PaymentOrderResponse(
                    order.get("id"),
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
}
