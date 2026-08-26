package com.ticketing.payment.controller;

import com.ticketing.payment.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final WebhookService webhookService;


    @PostMapping("/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(@RequestBody String payload) {
        // Signature is verified by RazorpayWebhookAuthFilter before this method runs.
        log.info("payload from razorpay is : {}", payload);
        webhookService.processPaymentWebhook(payload);
        return ResponseEntity.ok("Webhook received");
    }
}
