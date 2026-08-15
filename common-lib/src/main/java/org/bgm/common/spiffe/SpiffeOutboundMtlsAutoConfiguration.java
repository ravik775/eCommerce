package org.bgm.common.spiffe;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import javax.net.ssl.SSLContext;

/**
 * ADR-0002: outbound mTLS for servlet-based services making direct
 * service-to-service HTTP calls (not via the gateway or Kafka) — none
 * exist yet in this codebase (the current architecture is choreography-
 * saga over Kafka, ADR-0007), but this makes any future direct call
 * automatically mTLS-enforced rather than requiring each caller to wire
 * it individually. The gateway's outbound leg (its actual, currently-used
 * call path to backend services) uses the separate reactive/Netty
 * customizer instead, since the gateway is WebFlux, not Spring MVC.
 */
@AutoConfiguration
@ConditionalOnClass(CloseableHttpClient.class)
@ConditionalOnProperty(prefix = "spiffe.mtls", name = "enabled", havingValue = "true")
public class SpiffeOutboundMtlsAutoConfiguration {

    @Bean
    public RestTemplateCustomizer spiffeMtlsRestTemplateCustomizer(SSLContext sslContext) {
        return restTemplate -> restTemplate.setRequestFactory(mtlsRequestFactory(sslContext));
    }

    private ClientHttpRequestFactory mtlsRequestFactory(SSLContext sslContext) {
        // NoopHostnameVerifier: peer identity here is a SPIFFE ID (a URI
        // SAN like spiffe://ecommerce.local/order-service), not a DNS
        // name — SPIFFE SVIDs have no CN/DNS-SAN by design, so Apache
        // HttpClient5's default DefaultHostnameVerifier always rejects
        // them ("doesn't contain a common name and does not have
        // alternative names"), found live on the first genuine two-service
        // mTLS call. Trust is already established at the TLS layer: the
        // handshake requires the peer to present a certificate chaining to
        // this service's SPIFFE trust bundle (SpiffeMtlsAutoConfiguration's
        // acceptAnySpiffeId() SSLContext, verified NEED not WANT on the
        // inbound side — see SpiffeInboundMtlsAutoConfiguration), so a
        // DNS hostname match on top of that adds nothing; the reactive
        // gateway path (SpiffeGatewayMtlsAutoConfiguration) has no
        // equivalent check for the same reason.
        SSLConnectionSocketFactory sslSocketFactory =
                new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
