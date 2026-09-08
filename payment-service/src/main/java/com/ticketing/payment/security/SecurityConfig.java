package com.ticketing.payment.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.observation.SecurityObservationSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Spring Security auto-instruments its own filter chain/authorization/authentication
    // checks once it detects our ObservationRegistry bean — these fire on every request
    // including /actuator/health and /actuator/prometheus, and (unlike our own
    // ObservationPredicate) can't be filtered by path since the filter-chain context
    // carries no request info at all. Disabling entirely; app-level tracing is untouched.
    @Bean
    public SecurityObservationSettings securityObservationSettings() {
        return SecurityObservationSettings.noObservations();
    }

    // Not a @Component: it is wired into the chain below via addFilterBefore only.
    // Registering it as a bean too would make Spring Boot ALSO auto-register it as a
    // standalone global servlet filter, running signature verification twice per request.
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, @Value("${razorpay.webhook-secret}") String webhookSecret) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/webhooks/razorpay").hasAuthority("ROLE_WEBHOOK")
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(new RazorpayWebhookAuthFilter(webhookSecret), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
