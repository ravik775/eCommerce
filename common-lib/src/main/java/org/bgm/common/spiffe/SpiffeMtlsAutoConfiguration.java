package org.bgm.common.spiffe;

import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.exception.X509SourceException;
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

/**
 * ADR-0002: core SPIFFE mTLS wiring shared by both servlet (inbound
 * Tomcat) and reactive (outbound Netty/gateway) configurations —
 * {@link X509Source} is the single connection to the Workload API socket
 * (SVIDs are fetched once and kept fresh in-memory via the Workload API's
 * streaming updates, not re-fetched per request), and the
 * {@link SSLContext} built from it is shared for both directions.
 * <p>
 * Entirely opt-in via {@code spiffe.mtls.enabled=true} — services running
 * outside K8s (no SPIRE agent/CSI-mounted socket) never construct this
 * bean, so nothing about their existing behavior changes.
 */
@AutoConfiguration
@EnableConfigurationProperties(SpiffeMtlsProperties.class)
@ConditionalOnProperty(prefix = "spiffe.mtls", name = "enabled", havingValue = "true")
public class SpiffeMtlsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public X509Source spiffeX509Source(SpiffeMtlsProperties properties) throws IOException, SocketEndpointAddressException, X509SourceException {
        return DefaultX509Source.newSource(
                DefaultX509Source.X509SourceOptions.builder()
                        .spiffeSocketPath(properties.getWorkloadApiSocketPath())
                        .build());
    }

    /**
     * Trusts any peer whose SVID chains back to this service's own trust
     * bundle (fetched from the same Workload API connection) — SPIRE's
     * node/workload attestation is what establishes which SPIFFE ID a
     * peer is allowed to present in the first place; per-caller
     * authorization (which SPIFFE ID may call which route) is a
     * follow-up layered on top of this, not solved by the TLS handshake
     * itself. Every peer in this trust domain is mutually authenticated,
     * which is the actual zero-trust requirement (ADR-0006): no service
     * trusts a caller based on network location alone.
     */
    @Bean
    @ConditionalOnMissingBean
    public SSLContext spiffeSslContext(X509Source x509Source) throws NoSuchAlgorithmException, KeyManagementException {
        return SpiffeSslContextFactory.getSslContext(
                SpiffeSslContextFactory.SslContextOptions.builder()
                        .x509Source(x509Source)
                        .acceptAnySpiffeId()
                        .build());
    }
}
