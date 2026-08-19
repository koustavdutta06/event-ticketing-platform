package com.ticketing.payment.controller;

import com.ticketing.payment.dto.PaymentOrderRequest;
import com.ticketing.payment.dto.PaymentOrderResponse;
import com.ticketing.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/orders")
    public ResponseEntity<PaymentOrderResponse> createOrder(
            @Valid @RequestBody PaymentOrderRequest request) {
        return ResponseEntity.ok(
                paymentService.createOrder(request.bookingId(), request.seatId(), request.amount()));
    }
}
