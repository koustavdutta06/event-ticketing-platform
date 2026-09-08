package com.ticketing.inventory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.reporter.Sender;
import zipkin2.reporter.urlconnection.URLConnectionSender;

/**
 * Spring Boot 4.0.7's default Zipkin sender (java.net.http.HttpClient-based) throws
 * ClosedChannelException on every send against this cluster's networking. This
 * URLConnection-based sender is the classic, reliable implementation; defining it here
 * makes Boot's @ConditionalOnMissingBean(BytesMessageSender.class) back off from its own.
 */
@Configuration
public class ZipkinSenderConfig {

    @Bean
    public Sender zipkinSender(@Value("${management.zipkin.tracing.endpoint}") String endpoint) {
        return URLConnectionSender.create(endpoint);
    }
}
