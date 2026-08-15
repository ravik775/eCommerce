package org.bgm.catalogservice.client;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Seeds initial stock right after a provider/admin creates a product
 * (ProductService.create) — a synchronous call, not a new Kafka event
 * type, since this is a low-frequency, human-initiated action (not the
 * hot customer checkout path the async saga exists to protect).
 * <p>
 * Forwards the CALLING USER's own bearer token rather than minting a
 * service-account one (contrast payment-service's ServiceTokenProvider,
 * which needs one because its caller is a Kafka event, not an HTTP
 * request with a real user's token already attached): the same
 * provider/admin whose request reached catalog-service is the one
 * whose role (hasAnyRole('ADMIN','PROVIDER')) inventory-service's own
 * /inventory/add already requires, so relaying the token that got them
 * this far is simpler than provisioning a second service identity.
 */
@Component
public class InventoryServiceClient {

    private final RestTemplate restTemplate;

    public InventoryServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void addStock(long productId, int quantity, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("productId", productId, "quantity", quantity))
        );
        // https, not http: inventory-service enforces inbound mTLS
        // (ADR-0002), same as every other backend-to-backend call here.
        restTemplate.exchange(
                "https://inventory-service:8084/inventory/add", HttpMethod.POST,
                new HttpEntity<>(body, headers), Void.class);
    }
}
