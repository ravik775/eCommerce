package org.bgm.paymentservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

/**
 * Client-credentials token for payment-service's own service identity —
 * see OrderServiceClient's Javadoc for why it needs one at all. Deliberately
 * NOT Spring Security's full OAuth2 client machinery
 * (spring-boot-starter-oauth2-client + OAuth2AuthorizedClientManager):
 * this is the only outbound call in the whole codebase that needs a
 * service-account token, so the extra dependency/config surface isn't
 * worth it for one RestTemplate call — a plain token-endpoint POST,
 * cached until shortly before expiry, does the whole job.
 */
@Component
class ServiceTokenProvider {

    private final RestTemplate plainRestTemplate = new RestTemplate(); // NOT the mTLS-wired bean: Keycloak isn't SPIFFE-enrolled
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    ServiceTokenProvider(
            // Flat, single-level defaults, not application.properties'
            // nested-indirection form (that property's own value is
            // itself "${KEYCLOAK_TOKEN_URI:...}") — found live, 2026-08-16:
            // re-enabling this module's tests (see README's Code Quality
            // section) surfaced that Spring Boot's PlaceholderParser
            // fails to resolve that nested default in a plain
            // @SpringBootTest context (PlaceholderResolutionException on
            // contextLoads(), despite the property genuinely being
            // present and correctly formed in application.properties).
            // Root cause not fully isolated within this session's time
            // budget; these inline defaults sidestep it without
            // changing real-deployment behavior (K8s/Compose still
            // supply real values via env vars, which still win — a
            // property source with an actual value always beats a
            // @Value default).
            @Value("${payment-service.order-service-client.token-uri:http://keycloak:8080/realms/ecom/protocol/openid-connect/token}") String tokenUri,
            @Value("${payment-service.order-service-client.client-id:payment-service}") String clientId,
            @Value("${payment-service.order-service-client.client-secret:payment-service-dev-secret-CHANGE-IN-REAL-DEPLOYMENT}") String clientSecret) {
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    String getBearerToken() {
        // 30s safety margin: avoids a request racing the token's actual
        // expiry mid-flight rather than reacting only after a 401.
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry.minusSeconds(30))) {
            return cachedToken;
        }
        return fetchAndCacheToken();
    }

    private synchronized String fetchAndCacheToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry.minusSeconds(30))) {
            return cachedToken; // another thread already refreshed it while this one waited for the lock
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        var response = plainRestTemplate.postForObject(
                tokenUri, new org.springframework.http.HttpEntity<>(body, headers), TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Keycloak client_credentials token request returned no access_token");
        }

        cachedToken = response.accessToken();
        cachedTokenExpiry = Instant.now().plusSeconds(response.expiresIn());
        return cachedToken;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
