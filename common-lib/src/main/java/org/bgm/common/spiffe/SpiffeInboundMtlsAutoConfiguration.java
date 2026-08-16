package org.bgm.common.spiffe;

import io.spiffe.workloadapi.X509Source;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ADR-0002: enforces inbound mTLS on the embedded Tomcat connector.
 * Config is file-based (SVID/key/trust-bundle PEM files, rewritten
 * periodically by {@link SpiffePemFileWriter}) rather than wiring a
 * pre-built {@link javax.net.ssl.SSLContext} directly into
 * {@code SSLHostConfigCertificate} — that path has documented bugs in
 * some Spring Boot/Tomcat version combinations (the custom SSLContext
 * gets silently ignored; see spring-projects/spring-boot#47326), found
 * live while first implementing this class as a compile-then-verify
 * step, not assumed from the java-spiffe-provider README's example
 * alone. File-based config is the same mechanism SPIRE's own
 * {@code spiffe-helper} sidecar uses.
 * <p>
 * Rotation: X509 SVIDs from this realm's SPIRE server are short-lived
 * (~24h, see ADR-0002's live-verification section); this class rewrites
 * the PEM files and calls Tomcat's SSL reload every 5 minutes, which is
 * frequent enough to pick up a rotation well before expiry without
 * needing to hook the Workload API's push-based update stream directly.
 * <p>
 * {@code Connector} is not itself a Spring bean — Tomcat creates it
 * internally, after {@code WebServerFactoryCustomizer} runs — so the
 * customizer below captures the live reference into
 * {@link #liveConnectors} for the rotation task to reach later, rather
 * than (incorrectly) trying to {@code @Autowired} it.
 * <p>
 * Guards on {@link Connector} itself, not {@link TomcatServletWebServerFactory}
 * — the latter is always resolvable (it's a core Spring Boot class,
 * present even on the reactive gateway's classpath), so gating on it let
 * this configuration load on api-gateway and crash the whole app with
 * {@code NoClassDefFoundError: org/apache/catalina/connector/Connector},
 * found live on first deploy — the actually-scarce class (only present
 * when the real Tomcat embed jar is on the classpath, i.e. servlet-based
 * services) is what the guard must reference.
 */
@AutoConfiguration
@ConditionalOnClass(Connector.class)
@ConditionalOnProperty(prefix = "spiffe.mtls", name = "enabled", havingValue = "true")
public class SpiffeInboundMtlsAutoConfiguration {

    private static final Path PEM_DIR = Path.of(System.getProperty("java.io.tmpdir"), "spiffe-svid");
    // ADR-0051: was 5 minutes — found live to be wide enough that a
    // transient Workload API stream disconnect (SPIRE agent instability,
    // logged as "UNAVAILABLE: Network closed"/"io exception" on the gRPC
    // stream) could let the on-disk PEM cert's actual TTL run out before
    // the next scheduled reload, so the connector kept presenting an
    // already-expired certificate for the remainder of that window —
    // confirmed live: a gateway->user-service call rejected with
    // "certificate_expired" roughly a minute after such a disconnect.
    // 30 seconds shrinks that exposure window ~10x for the common case
    // (a rotation happened, this just hadn't picked it up yet); it does
    // NOT help the harder case where the Workload API itself is down and
    // X509Source has nothing fresher to give regardless of poll
    // frequency — see checkExpiryMargin() below for that case.
    private static final long RELOAD_INTERVAL_SECONDS = 30;
    // If the currently-active SVID has less than this much validity left
    // at poll time, something is already wrong upstream (a healthy
    // Workload API connection would have delivered a new SVID well
    // before this point, since SPIRE agents rotate at roughly half the
    // SVID's TTL) — logged at WARN so it's visible before the cert
    // actually expires, not just after, per this project's "surface a
    // gap before it's found by someone else" pattern (see ADR-0048).
    private static final Duration EXPIRY_WARNING_MARGIN = Duration.ofMinutes(2);

    private final List<Connector> liveConnectors = new CopyOnWriteArrayList<>();

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> spiffeTomcatMtlsCustomizer(X509Source x509Source) {
        return factory -> factory.addConnectorCustomizers(connector -> {
            configureConnectorForMtls(connector, SpiffePemFileWriter.writeCurrentSvid(x509Source, PEM_DIR));
            liveConnectors.add(connector);
        });
    }

    /**
     * Registered as a {@link SmartLifecycle} so it starts after the web
     * server (and therefore the connector customizer above) has run.
     */
    @Bean
    public SmartLifecycle spiffeSvidRotationTask(X509Source x509Source) {
        return new SvidRotationLifecycle(x509Source, liveConnectors);
    }

    private static void configureConnectorForMtls(Connector connector, SpiffePemFileWriter.PemPaths pem) {
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");

        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setSslProtocol("TLS");
        // NEED, not WANT: a peer without a valid SPIFFE SVID must be
        // rejected at the TLS layer, not merely left unauthenticated at
        // the application layer — that's the actual zero-trust
        // requirement (ADR-0006), not a softer "prefer but allow" check.
        sslHostConfig.setCertificateVerification("required");
        // NOT setCaCertificateFile(...): that property is OpenSSL-syntax
        // only — under JSSE (this connector's actual mode, no APR/OpenSSL
        // native library present), Tomcat logs a warning and silently
        // ignores it, leaving the JSSE default (near-empty) trust store
        // in place. The connector still *asked* for a client cert
        // (certificateVerification=required forces that unconditionally)
        // so a no-cert connection was correctly rejected either way —
        // masking the bug until a real peer SVID was presented and
        // failed validation with certificate_unknown, found live on the
        // first genuine two-service mTLS call. Fixed by building the
        // trust store as an in-memory KeyStore and setting it directly,
        // which works under both JSSE and OpenSSL syntax.
        sslHostConfig.setTrustStore(buildTrustStore(pem.caCertificateFile()));

        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setCertificateFile(pem.certificateFile().toString());
        certificate.setCertificateKeyFile(pem.certificateKeyFile().toString());
        certificate.setCertificateChainFile(pem.certificateChainFile().toString());
        sslHostConfig.addCertificate(certificate);

        connector.addSslHostConfig(sslHostConfig);
    }

    private static KeyStore buildTrustStore(Path caCertificateFile) {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            try (InputStream in = Files.newInputStream(caCertificateFile)) {
                int i = 0;
                for (Certificate certificate : certificateFactory.generateCertificates(in)) {
                    trustStore.setCertificateEntry("spire-ca-" + i++, certificate);
                }
            }
            return trustStore;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Unable to read SPIFFE trust bundle", e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build trust store from SPIFFE trust bundle", e);
        }
    }

    private static final class SvidRotationLifecycle implements SmartLifecycle {
        private static final Logger log = LoggerFactory.getLogger(SvidRotationLifecycle.class);

        private final X509Source x509Source;
        private final List<Connector> connectors;
        private ScheduledExecutorService executor;
        private volatile boolean running;

        SvidRotationLifecycle(X509Source x509Source, List<Connector> connectors) {
            this.x509Source = x509Source;
            this.connectors = connectors;
        }

        @Override
        public void start() {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "spiffe-svid-rotation");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(
                    this::rotate, RELOAD_INTERVAL_SECONDS, RELOAD_INTERVAL_SECONDS, TimeUnit.SECONDS);
            running = true;
        }

        private void rotate() {
            // Must not let any exception escape: scheduleAtFixedRate
            // silently and permanently cancels all future executions of a
            // task that throws, with nothing logged anywhere — found live
            // as the root cause of an expired, never-refreshed SVID (7
            // successful 5-minute rotations, then the schedule died with
            // no trace and the cert expired ~24h later). A transient
            // Workload API hiccup here must cost at most one missed
            // rotation, not all of them.
            try {
                checkExpiryMargin();
                SpiffePemFileWriter.writeCurrentSvid(x509Source, PEM_DIR);
                for (Connector connector : connectors) {
                    // reloadSslHostConfigs() lives on the protocol handler
                    // (which delegates to its endpoint), not on Connector
                    // itself — found live via "cannot find symbol" on the
                    // first, more obvious-looking attempt.
                    if (connector.getProtocolHandler() instanceof AbstractHttp11JsseProtocol<?> jsseProtocol) {
                        jsseProtocol.reloadSslHostConfigs();
                    }
                }
            } catch (Exception e) {
                log.error("SPIFFE SVID rotation failed; will retry at the next scheduled interval", e);
            }
        }

        // ADR-0051: java-spiffe's X509Source has no public
        // watcher/callback API (verified by decompiling
        // java-spiffe-core — DefaultX509Source's own internal watcher is
        // private) and getX509Svid() never proactively invalidates on a
        // Workload API disconnect, it just keeps returning the last
        // successfully-pushed snapshot. So polling frequency alone can't
        // detect "the Workload API is down and nothing fresher is
        // coming" — this check makes that condition visible (a WARN
        // before expiry) instead of silent until a real handshake fails.
        private void checkExpiryMargin() {
            try {
                Instant notAfter = x509Source.getX509Svid().getChain().get(0).getNotAfter().toInstant();
                Duration remaining = Duration.between(Instant.now(), notAfter);
                if (remaining.compareTo(EXPIRY_WARNING_MARGIN) < 0) {
                    log.warn("SPIFFE SVID expires in {} — Workload API may not be delivering rotations "
                                    + "(a healthy connection rotates well before this point); "
                                    + "connectors will start rejecting/being rejected once it lapses",
                            remaining);
                }
            } catch (Exception e) {
                log.warn("Unable to check SPIFFE SVID expiry margin", e);
            }
        }

        @Override
        public void stop() {
            if (executor != null) {
                executor.shutdownNow();
            }
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
