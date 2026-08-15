package org.bgm.paymentservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * The one legitimate synchronous cross-service call left in the
 * choreography saga (ADR-0007): `inventory-reserved` doesn't carry the
 * order's amount (that's order-service's domain, not inventory's — see
 * InventoryReservedEvent), so payment-service looks it up directly.
 * Resilience4j-guarded per the original Phase 2 DoD intent, applied here
 * since it's this saga's only remaining sync call.
 */
@Component
@RequiredArgsConstructor
public class OrderServiceClient {

    private final RestTemplate restTemplate;

    @CircuitBreaker(name = "orderServiceLookup", fallbackMethod = "fallbackGetOrderAmount")
    @Retry(name = "orderServiceLookup")
    public double getOrderAmount(long orderId) {
        // https, not http: order-service enforces inbound mTLS (ADR-0002)
        // once SPIFFE_MTLS_ENABLED=true; restTemplate gets
        // SpiffeOutboundMtlsAutoConfiguration's client-SVID-bearing
        // RequestFactory the same way, but only for https:// requests —
        // an http:// URL bypasses that SSLContext entirely and would just
        // get order-service's plaintext-rejection response.
        //
        // Direct K8s Service DNS, not the Eureka-style "ORDER-SERVICE"
        // logical hostname: per ADR-0008 this deployment has no Eureka
        // server, so a @LoadBalanced RestTemplate has no ServiceInstance
        // to resolve "ORDER-SERVICE" to and fails with "Service Instance
        // cannot be null, serviceId: ORDER-SERVICE" (confirmed live).
        OrderLookupResponse response = restTemplate.getForObject(
                "https://order-service:8082/orders/" + orderId, OrderLookupResponse.class);
        if (response == null || response.totalAmount() == null) {
            throw new OrderLookupException(orderId, null);
        }
        return response.totalAmount();
    }

    @SuppressWarnings("unused") // Resilience4j fallback signature convention: same args + Throwable
    private double fallbackGetOrderAmount(long orderId, Throwable t) {
        throw new OrderLookupException(orderId, t);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderLookupResponse(long id, Double totalAmount) {
    }
}
