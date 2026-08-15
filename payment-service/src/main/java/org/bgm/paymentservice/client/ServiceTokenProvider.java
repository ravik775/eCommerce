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
            @Value("${payment-service.order-service-client.token-uri}") String tokenUri,
            @Value("${payment-service.order-service-client.client-id}") String clientId,
            @Value("${payment-service.order-service-client.client-secret}") String clientSecret) {
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
