package org.bgm.paymentservice.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    // Plain RestTemplate: no Eureka server exists in this deployment
    // (ADR-0008 — K8s resolves peers via K8s Service DNS instead), so
    // callers address services by their K8s Service DNS name directly
    // rather than going through @LoadBalanced's serviceId resolution.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
