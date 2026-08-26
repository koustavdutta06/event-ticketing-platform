// payment-service: service/OrderTrackingService.java
package com.ticketing.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTrackingService {

    private static final String BOOKING_KEY_PREFIX  = "order:booking:";
    private static final String SEAT_KEY_PREFIX     = "order:seat:";
    private static final Duration TTL               = Duration.ofHours(24);
    // 24 hours — well beyond any realistic payment window,
    // but not so long that stale entries accumulate forever

    private final RedisTemplate<String, String> redisTemplate;

    public void track(String razorpayOrderId, Long bookingId, Long seatId) {
        redisTemplate.opsForValue()
                .set(BOOKING_KEY_PREFIX + razorpayOrderId, bookingId.toString(), TTL);
        redisTemplate.opsForValue()
                .set(SEAT_KEY_PREFIX + razorpayOrderId, seatId.toString(), TTL);

        log.info("Tracking Razorpay order {} → booking {}, seat {}",
                razorpayOrderId, bookingId, seatId);
    }

    public Long getBookingId(String razorpayOrderId) {
        String value = redisTemplate.opsForValue().get(BOOKING_KEY_PREFIX + razorpayOrderId);
        if (value == null) {
            log.warn("No booking tracking found for Razorpay order: {}", razorpayOrderId);
            return null;
        }
        return Long.parseLong(value);
    }

    public Long getSeatId(String razorpayOrderId) {
        String value = redisTemplate.opsForValue().get(SEAT_KEY_PREFIX + razorpayOrderId);
        if (value == null) {
            log.warn("No seat tracking found for Razorpay order: {}", razorpayOrderId);
            return null;
        }
        return Long.parseLong(value);
    }

    public void remove(String razorpayOrderId) {
        // call this after webhook processing completes successfully —
        // keeps Redis clean rather than relying purely on TTL expiry
        redisTemplate.delete(BOOKING_KEY_PREFIX + razorpayOrderId);
        redisTemplate.delete(SEAT_KEY_PREFIX + razorpayOrderId);
        log.info("Removed tracking for completed Razorpay order: {}", razorpayOrderId);
    }
}