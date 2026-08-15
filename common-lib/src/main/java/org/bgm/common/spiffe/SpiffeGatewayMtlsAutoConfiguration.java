package org.bgm.common.spiffe;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.spiffe.provider.SpiffeKeyManager;
import io.spiffe.provider.SpiffeTrustManager;
import io.spiffe.workloadapi.X509Source;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import reactor.netty.http.client.HttpClient;

/**
 * ADR-0002: outbound mTLS for Spring Cloud Gateway's proxy client — the
 * gateway is WebFlux/reactor-netty, not Spring MVC, so its HTTP client
 * is configured through Netty's own {@link SslContext}
 * ({@code io.netty.handler.ssl}), not {@code javax.net.ssl.SSLContext}
 * (that's what {@link SpiffeMtlsAutoConfiguration}'s bean is for, used
 * by the separate servlet-side inbound/outbound configurations instead).
 * {@link HttpClientCustomizer} is Spring Cloud Gateway's own supported
 * extension point for exactly this — not a workaround.
 * <p>
 * This is the gateway's real, currently-used call path (proxying every
 * route to a backend service via TokenRelay, ADR-0025), unlike
 * {@code SpiffeOutboundMtlsAutoConfiguration}'s RestTemplate wiring,
 * which no service currently exercises.
 */
@AutoConfiguration
@ConditionalOnClass(HttpClient.class)
@ConditionalOnProperty(prefix = "spiffe.mtls", name = "enabled", havingValue = "true")
public class SpiffeGatewayMtlsAutoConfiguration {

    @Bean
    public HttpClientCustomizer spiffeGatewayHttpClientCustomizer(X509Source x509Source) {
        SslContext sslContext = buildNettySslContext(x509Source);
        return httpClient -> httpClient.secure(spec -> spec.sslContext(sslContext));
    }

    private SslContext buildNettySslContext(X509Source x509Source) {
        try {
            return SslContextBuilder.forClient()
                    .keyManager(new SpiffeKeyManager(x509Source))
                    .trustManager(new SpiffeTrustManager(x509Source))
                    .build();
        } catch (javax.net.ssl.SSLException e) {
            throw new IllegalStateException("Unable to build Netty SslContext from SPIFFE X509Source", e);
        }
    }
}
