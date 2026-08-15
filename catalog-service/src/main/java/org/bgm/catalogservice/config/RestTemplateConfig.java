package org.bgm.catalogservice.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Plain RestTemplate, K8s Service DNS addressing — same reasoning as
 * payment-service's RestTemplateConfig (ADR-0008: no Eureka server in
 * this deployment, no @LoadBalanced).
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
