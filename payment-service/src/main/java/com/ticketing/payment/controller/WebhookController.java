package com.ticketing.payment.controller;

import com.razorpay.Utils;
import com.ticketing.payment.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final WebhookService webhookService;

    @Value("${razorpay.webhook-secret:#{null}}")
    private String webhookSecret;

    @PostMapping("/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String signature) {

        // Step 1: Verify signature — reject anything that doesn't match
        try {
            boolean valid = Utils.verifyWebhookSignature(payload, signature, webhookSecret);
            if (!valid) {
                log.warn("Invalid Razorpay webhook signature — rejecting");
                return ResponseEntity.status(400).body("Invalid signature");
            }
        } catch (Exception e) {
            log.error("Webhook signature verification failed: {}", e.getMessage());
            return ResponseEntity.status(400).body("Signature verification error");
        }

        // Step 2: Process idempotently
        webhookService.processPaymentWebhook(payload);

        // Step 3: Return 200 immediately so Razorpay doesn't retry unnecessarily
        return ResponseEntity.ok("Webhook received");
    }
}
