package com.ticketing.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Async
    public void sendSeatHeldEmail(String toEmail, Long seatId, String expiresAt) {
        send(toEmail,
                "Your seat is held — complete payment soon",
                String.format("""
                Your seat %d has been reserved.
                Please complete your payment before %s to confirm your booking.
                If payment is not received, your seat will be automatically released.
                """, seatId, expiresAt));
    }

    @Async
    public void sendBookingConfirmedEmail(String toEmail, Long bookingId, Long seatId) {
        send(toEmail,
                "Booking Confirmed!",
                String.format("""
                Great news! Your booking %d for seat %d is confirmed.
                Thank you for your payment. Enjoy the event!
                """, bookingId, seatId));
    }

    @Async
    public void sendBookingCancelledEmail(String toEmail, Long bookingId, String reason) {
        send(toEmail,
                "Booking Cancelled",
                String.format("""
                Your booking %d has been cancelled.
                Reason: %s
                If payment was taken, a refund will be processed shortly.
                """, bookingId, reason));
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("onboarding@resend.dev"); // use a verified sender in Brevo dashboard
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email sent to {}: {}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }
}
