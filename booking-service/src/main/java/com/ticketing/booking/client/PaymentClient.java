package com.ticketing.booking.client;

import com.ticketing.booking.dto.PaymentOrderRequest;
import com.ticketing.booking.dto.PaymentOrderResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class PaymentClient {

    private final WebClient paymentWebClient;

    public PaymentClient(@Qualifier("paymentWebClient") WebClient paymentWebClient) {
        this.paymentWebClient = paymentWebClient;
    }

    @CircuitBreaker(name = "paymentService", fallbackMethod = "createPaymentOrderFallback")
    @Retry(name = "paymentService")
    @TimeLimiter(name = "paymentService")
    public CompletableFuture<PaymentOrderResponse> createPaymentOrder(Long bookingId, Long seatId,
                                                                      BigDecimal amount) {
        return paymentWebClient.post()
                .uri("/api/v1/payments/orders")
                .bodyValue(new PaymentOrderRequest(bookingId, seatId, amount))
                .retrieve()
                .bodyToMono(PaymentOrderResponse.class)
                .toFuture();
    }

    public CompletableFuture<PaymentOrderResponse> createPaymentOrderFallback(
            Long bookingId, Long seatId, BigDecimal amount, Throwable throwable) {
        log.error("payment-service circuit open or retries exhausted for booking {}: {}",
            bookingId, throwable.getMessage());
        throw new RuntimeException(
            "Payment service is temporarily unavailable — please try again shortly", throwable);
    }
}