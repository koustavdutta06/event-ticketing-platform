package com.ticketing.payment.security;

import com.razorpay.Utils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Authenticates inbound Razorpay webhook calls by verifying the HMAC signature Razorpay
 * sends in the X-Razorpay-Signature header. Runs ahead of the rest of the Spring Security
 * chain so that an invalid/missing signature never reaches the controller, and a valid one
 * is represented as a real Authentication (ROLE_WEBHOOK) enforced by the SecurityFilterChain.
 */
@Slf4j
public class RazorpayWebhookAuthFilter extends OncePerRequestFilter {

    private static final String WEBHOOK_PATH = "/api/v1/webhooks/razorpay";
    private static final String SIGNATURE_HEADER = "X-Razorpay-Signature";

    private final String webhookSecret;

    public RazorpayWebhookAuthFilter(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && WEBHOOK_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String signature = request.getHeader(SIGNATURE_HEADER);

        if (signature == null || signature.isBlank()) {
            log.warn("Razorpay webhook call missing {} header — rejecting", SIGNATURE_HEADER);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing signature");
            return;
        }

        try {
            String payload = new String(cachedRequest.getCachedBody(), StandardCharsets.UTF_8);
            if (!Utils.verifyWebhookSignature(payload, signature, webhookSecret)) {
                throw new BadCredentialsException("Signature mismatch");
            }
        } catch (BadCredentialsException e) {
            log.warn("Invalid Razorpay webhook signature — rejecting");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid signature");
            return;
        } catch (Exception e) {
            log.error("Webhook signature verification failed: {}", e.getMessage());
            throw new AuthenticationServiceException("Webhook signature verification error", e);
        }

        var authentication = new PreAuthenticatedAuthenticationToken(
                "razorpay-webhook", null, List.of(new SimpleGrantedAuthority("ROLE_WEBHOOK")));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(cachedRequest, response);
    }
}
