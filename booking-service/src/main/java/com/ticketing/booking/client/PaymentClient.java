package com.ticketing.booking.client;

import com.ticketing.booking.dto.PaymentOrderRequest;
import com.ticketing.booking.dto.PaymentOrderResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class PaymentClient {

    private final WebClient paymentWebClient;

    public PaymentClient(@Qualifier("paymentWebClient") WebClient paymentWebClient) {
        this.paymentWebClient = paymentWebClient;
    }

    public Mono<PaymentOrderResponse> createPaymentOrder(Long bookingId, Long seatId,
                                                         java.math.BigDecimal amount) {
        return paymentWebClient.post()
                .uri("/api/v1/payments/orders")
                .bodyValue(new PaymentOrderRequest(bookingId, seatId, amount))
                .retrieve()
                .bodyToMono(PaymentOrderResponse.class);
    }
}