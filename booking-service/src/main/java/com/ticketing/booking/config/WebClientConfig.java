package com.ticketing.booking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient inventoryWebClient(WebClient.Builder builder, @Value("${inventory-service.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public WebClient catalogWebClient(WebClient.Builder builder, @Value("${catalog-service.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public WebClient paymentWebClient(WebClient.Builder builder, @Value("${payment-service.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
